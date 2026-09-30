package com.example.pagos;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class PagoController {
  private final JdbcTemplate db;
  private final RabbitTemplate rabbit;
  @Value("${app.bank.timeout-ms:2500}") long bankTimeoutMs;

  public PagoController(JdbcTemplate db, RabbitTemplate rabbit) {
    this.db = db; this.rabbit = rabbit;
  }

  @GetMapping("/cuentas")
  public List<Map<String,Object>> cuentas() {
    return db.queryForList("select numero,titular,saldo,limite_diario as \"limiteDiario\",usado_hoy as \"usadoHoy\",activa from cuentas order by numero");
  }

  @GetMapping("/pagos")
  public List<Map<String,Object>> pagos() {
    return db.queryForList("select id,idempotency_key as \"idempotencyKey\",cuenta_origen as \"cuentaOrigen\",beneficiario,monto,estado,mensaje,referencia_banco as \"referenciaBanco\",creado_en as \"creadoEn\",actualizado_en as \"actualizadoEn\",latencia_ms as \"latenciaMs\",conciliado_en as \"conciliadoEn\" from pagos order by creado_en desc");
  }

  @GetMapping("/auditoria")
  public List<Map<String,Object>> auditoria() {
    return db.queryForList("select id,pago_id as \"pagoId\",idempotency_key as \"idempotencyKey\",accion,resultado,detalle,fecha from auditoria order by fecha desc limit 50");
  }

  @PostMapping("/pagos")
  @Operation(summary = "Solicitar autorización de un pago")
  @Transactional
  public Map<String,Object> autorizar(@RequestHeader("Idempotency-Key") String key, @RequestBody Solicitud s) {
    long inicio = System.currentTimeMillis();
    if (key == null || key.isBlank()) throw new IllegalArgumentException("Se requiere Idempotency-Key");

    var existentes = db.queryForList("select * from pagos where idempotency_key=?", key);
    if (!existentes.isEmpty()) {
      var p = existentes.get(0);
      auditar(((Number)p.get("id")).longValue(), key, "REINTENTO_IDEMPOTENTE", "SIN_NUEVO_COBRO", "Se devolvió el pago existente y no se realizó otro cobro.");
      return Map.of("respuestaIdempotente", true, "pago", pagoPorId(((Number)p.get("id")).longValue()));
    }

    Long pagoId = db.queryForObject(
      "insert into pagos(idempotency_key,cuenta_origen,beneficiario,monto,estado,mensaje) values(?,?,?,?,?,?) returning id",
      Long.class, key, s.cuentaOrigen(), s.beneficiario(), s.monto(), "RECIBIDO", "Solicitud recibida"
    );
    auditar(pagoId, key, "SOLICITUD_RECIBIDA", "OK", "Cuenta="+s.cuentaOrigen()+", monto="+s.monto());

    var cuentas = db.queryForList("select * from cuentas where numero=?", s.cuentaOrigen());
    if (cuentas.isEmpty()) return rechazar(pagoId,key,inicio,"Cuenta inexistente");
    var c = cuentas.get(0);
    if (!(Boolean)c.get("activa")) return rechazar(pagoId,key,inicio,"Cuenta inactiva");
    double saldo = ((Number)c.get("saldo")).doubleValue();
    double limite = ((Number)c.get("limite_diario")).doubleValue();
    double usado = ((Number)c.get("usado_hoy")).doubleValue();
    if (s.monto() <= 0) return rechazar(pagoId,key,inicio,"El monto debe ser mayor que cero");
    if (s.monto() > saldo) return rechazar(pagoId,key,inicio,"Saldo insuficiente");
    if (usado + s.monto() > limite) return rechazar(pagoId,key,inicio,"Supera el límite diario");
    auditar(pagoId,key,"VALIDACIONES_SINCRONAS","OK","Cuenta, saldo y límite verificados.");

    try {
      BancoRespuesta br = bancoExterno(s.monto(), s.modoPrueba());
      if (!br.autorizado()) return rechazar(pagoId,key,inicio,br.mensaje());

      db.update("update cuentas set saldo=saldo-?, usado_hoy=usado_hoy+? where numero=?", s.monto(), s.monto(), s.cuentaOrigen());
      long lat = System.currentTimeMillis()-inicio;
      db.update("update pagos set estado='AUTORIZADO',mensaje=?,referencia_banco=?,latencia_ms=?,actualizado_en=now() where id=?", br.mensaje(), br.referencia(), lat, pagoId);
      auditar(pagoId,key,"AUTORIZACION_BANCO","AUTORIZADO","Referencia externa="+br.referencia());
      rabbit.convertAndSend("", "pagos.notificaciones", Map.of("pagoId",pagoId,"estado","AUTORIZADO"));
      rabbit.convertAndSend("", "pagos.conciliacion", Map.of("pagoId",pagoId,"estado","AUTORIZADO"));
      return Map.of("respuestaIdempotente", false, "pago", pagoPorId(pagoId));
    } catch (Exception ex) {
      long lat = System.currentTimeMillis()-inicio;
      db.update("update pagos set estado='PENDIENTE_CONCILIACION',mensaje=?,latencia_ms=?,actualizado_en=now() where id=?", "Resultado externo incierto: "+ex.getMessage(), lat, pagoId);
      auditar(pagoId,key,"BANCO_EXTERNO","SIN_RESPUESTA",ex.getMessage()+". No se reintenta el cobro automáticamente; pasa a conciliación.");
      rabbit.convertAndSend("", "pagos.conciliacion", Map.of("pagoId",pagoId,"estado","PENDIENTE_CONCILIACION"));
      return Map.of("respuestaIdempotente", false, "pago", pagoPorId(pagoId));
    }
  }

  @PostMapping("/pagos/{id}/conciliar")
  @Transactional
  public Map<String,Object> conciliar(@PathVariable long id, @RequestParam boolean confirmado) {
    var p = pagoPorId(id);
    if (!"PENDIENTE_CONCILIACION".equals(p.get("estado"))) throw new IllegalStateException("El pago no está pendiente de conciliación");
    String key = String.valueOf(p.get("idempotencyKey"));
    double monto = ((Number)p.get("monto")).doubleValue();
    String cuenta = String.valueOf(p.get("cuentaOrigen"));

    if (confirmado) {
      var c = db.queryForMap("select saldo from cuentas where numero=?", cuenta);
      double saldo = ((Number)c.get("saldo")).doubleValue();
      if (saldo >= monto) {
        db.update("update cuentas set saldo=saldo-?,usado_hoy=usado_hoy+? where numero=?", monto,monto,cuenta);
        db.update("update pagos set estado='CONCILIADO',mensaje='Banco confirmó posteriormente el pago',referencia_banco=?,conciliado_en=now(),actualizado_en=now() where id=?", "BANK-LATE-"+id,id);
        auditar(id,key,"CONCILIACION","CONCILIADO","El banco confirmó posteriormente el pago.");
      } else {
        db.update("update pagos set estado='FALLIDO',mensaje='No pudo aplicarse la confirmación tardía por saldo insuficiente',conciliado_en=now(),actualizado_en=now() where id=?",id);
        auditar(id,key,"CONCILIACION","FALLIDO","Saldo insuficiente al conciliar.");
      }
    } else {
      db.update("update pagos set estado='FALLIDO',mensaje='Banco confirmó que el pago no fue procesado',conciliado_en=now(),actualizado_en=now() where id=?",id);
      auditar(id,key,"CONCILIACION","FALLIDO","El banco confirmó que no procesó el pago.");
    }
    rabbit.convertAndSend("", "pagos.notificaciones", Map.of("pagoId",id,"estado",pagoPorId(id).get("estado")));
    return pagoPorId(id);
  }

  @GetMapping("/metricas")
  public Map<String,Object> metricas() {
    var r = db.queryForMap("""
      select
        coalesce(avg(latencia_ms),0) as latencia,
        count(*) as total,
        count(*) filter (where estado in ('RECHAZADO','FALLIDO')) as errores,
        coalesce(avg(extract(epoch from (conciliado_en-creado_en))/60) filter (where conciliado_en is not null),0) as conciliacion
      from pagos
    """);
    long total=((Number)r.get("total")).longValue(), errores=((Number)r.get("errores")).longValue();
    long reintentos = db.queryForObject("select count(*) from auditoria where accion='REINTENTO_IDEMPOTENTE'", Long.class);
    return Map.of(
      "latenciaAutorizacionPromedioMs", ((Number)r.get("latencia")).doubleValue(),
      "pagosDuplicadosEvitados", reintentos,
      "tasaErroresPorcentaje", total==0?0:errores*100.0/total,
      "tiempoConciliacionPromedioMinutos", ((Number)r.get("conciliacion")).doubleValue()
    );
  }

  private Map<String,Object> rechazar(long id,String key,long inicio,String motivo){
    db.update("update pagos set estado='RECHAZADO',mensaje=?,latencia_ms=?,actualizado_en=now() where id=?",motivo,System.currentTimeMillis()-inicio,id);
    auditar(id,key,"PAGO_RECHAZADO","RECHAZADO",motivo);
    rabbit.convertAndSend("", "pagos.notificaciones", Map.of("pagoId",id,"estado","RECHAZADO"));
    return Map.of("respuestaIdempotente",false,"pago",pagoPorId(id));
  }

  private Map<String,Object> pagoPorId(long id){
    return db.queryForMap("select id,idempotency_key as \"idempotencyKey\",cuenta_origen as \"cuentaOrigen\",beneficiario,monto,estado,mensaje,referencia_banco as \"referenciaBanco\",creado_en as \"creadoEn\",actualizado_en as \"actualizadoEn\",latencia_ms as \"latenciaMs\",conciliado_en as \"conciliadoEn\" from pagos where id=?",id);
  }

  private void auditar(Long id,String key,String accion,String resultado,String detalle){
    db.update("insert into auditoria(pago_id,idempotency_key,accion,resultado,detalle) values(?,?,?,?,?)",id,key,accion,resultado,detalle);
  }

  private BancoRespuesta bancoExterno(double monto,String modo) throws Exception {
    String m = modo==null?"OK":modo.toUpperCase();
    if ("ERROR".equals(m)) throw new RuntimeException("Banco externo no disponible");
    if ("TIMEOUT".equals(m)) { Thread.sleep(bankTimeoutMs+700); throw new RuntimeException("Banco externo no respondió a tiempo"); }
    if (monto>5000) return new BancoRespuesta(false,null,"Monto rechazado por el banco externo");
    return new BancoRespuesta(true,"BANK-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),"Autorizado por banco externo");
  }

  public record Solicitud(String cuentaOrigen,String beneficiario,double monto,String modoPrueba){}
  public record BancoRespuesta(boolean autorizado,String referencia,String mensaje){}
}
