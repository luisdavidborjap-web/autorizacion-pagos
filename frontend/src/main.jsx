import React,{useEffect,useState} from 'react';
import ReactDOM from 'react-dom/client';
import './styles.css';

async function api(url,options={}){const r=await fetch(url,{headers:{'Content-Type':'application/json',...(options.headers||{})},...options});if(!r.ok)throw new Error(await r.text());return r.json()}

function App(){
 const [cuentas,setCuentas]=useState([]),[pagos,setPagos]=useState([]),[audit,setAudit]=useState([]),[metricas,setMetricas]=useState({}),[mensaje,setMensaje]=useState('');
 const [form,setForm]=useState({cuentaOrigen:'CTA-001',beneficiario:'Comercio Demo',monto:100,modoPrueba:'OK',idempotencyKey:crypto.randomUUID()});
 const cargar=async()=>{const[c,p,a,m]=await Promise.all([api('/api/cuentas'),api('/api/pagos'),api('/api/auditoria'),api('/api/metricas')]);setCuentas(c);setPagos(p);setAudit(a);setMetricas(m)};
 useEffect(()=>{cargar()},[]);
 const enviar=async e=>{e.preventDefault();try{const r=await api('/api/pagos',{method:'POST',headers:{'Idempotency-Key':form.idempotencyKey},body:JSON.stringify({cuentaOrigen:form.cuentaOrigen,beneficiario:form.beneficiario,monto:Number(form.monto),modoPrueba:form.modoPrueba})});setMensaje(r.respuestaIdempotente?'Reintento detectado: no se realizó un segundo cobro.':`${r.pago.estado}: ${r.pago.mensaje}`);await cargar()}catch(e){setMensaje('Error: '+e.message)}};
 const conciliar=async(id,confirmado)=>{try{await api(`/api/pagos/${id}/conciliar?confirmado=${confirmado}`,{method:'POST'});setMensaje('Conciliación registrada.');await cargar()}catch(e){setMensaje('Error: '+e.message)}};
 return <main>
  <header><div><h1>Autorización de pagos</h1><p>Validación · límites · idempotencia · auditoría · conciliación</p></div><a href="/swagger" target="_blank">OpenAPI / Swagger</a></header>
  {mensaje&&<div className="notice">{mensaje}</div>}
  <section className="metrics">
   <article><span>Latencia promedio</span><strong>{Number(metricas.latenciaAutorizacionPromedioMs||0).toFixed(0)} ms</strong></article>
   <article><span>Duplicados evitados</span><strong>{metricas.pagosDuplicadosEvitados||0}</strong></article>
   <article><span>Tasa de errores</span><strong>{Number(metricas.tasaErroresPorcentaje||0).toFixed(0)}%</strong></article>
   <article><span>Conciliación promedio</span><strong>{Number(metricas.tiempoConciliacionPromedioMinutos||0).toFixed(1)} min</strong></article>
  </section>
  <section className="grid"><div className="panel"><h2>Solicitar pago</h2><form onSubmit={enviar}>
   <label>Cuenta<select value={form.cuentaOrigen} onChange={e=>setForm({...form,cuentaOrigen:e.target.value})}>{cuentas.map(c=><option value={c.numero} key={c.numero}>{c.numero} — {c.titular}</option>)}</select></label>
   <label>Beneficiario<input value={form.beneficiario} onChange={e=>setForm({...form,beneficiario:e.target.value})}/></label>
   <label>Monto<input type="number" min="0.01" step="0.01" value={form.monto} onChange={e=>setForm({...form,monto:e.target.value})}/></label>
   <label>Banco externo<select value={form.modoPrueba} onChange={e=>setForm({...form,modoPrueba:e.target.value})}><option value="OK">Responder OK</option><option value="ERROR">Fallar</option><option value="TIMEOUT">No responder a tiempo</option></select></label>
   <label>Idempotency-Key<input value={form.idempotencyKey} onChange={e=>setForm({...form,idempotencyKey:e.target.value})}/></label>
   <div className="buttons"><button>Autorizar pago</button><button type="button" className="secondary" onClick={()=>setForm({...form,idempotencyKey:crypto.randomUUID()})}>Nueva clave</button></div>
  </form><p className="hint">Envía dos veces con la misma clave para demostrar que el sistema evita el cobro duplicado.</p></div>
  <div className="panel"><h2>Cuentas</h2>{cuentas.map(c=><div className="account" key={c.numero}><strong>{c.numero}</strong><span>{c.titular}</span><span>Saldo: ${Number(c.saldo).toFixed(2)}</span><span>Límite: ${Number(c.limiteDiario).toFixed(2)}</span>{!c.activa&&<b className="bad">INACTIVA</b>}</div>)}</div></section>
  <section><h2>Pagos</h2><div className="table-wrap"><table><thead><tr><th>ID</th><th>Cuenta</th><th>Monto</th><th>Estado</th><th>Latencia</th><th>Resultado</th><th>Conciliación</th></tr></thead><tbody>{pagos.map(p=><tr key={p.id}><td>{p.id}</td><td>{p.cuentaOrigen}</td><td>${Number(p.monto).toFixed(2)}</td><td><span className={`status ${String(p.estado).toLowerCase()}`}>{p.estado}</span></td><td>{p.latenciaMs??'-'} ms</td><td>{p.mensaje}</td><td>{p.estado==='PENDIENTE_CONCILIACION'&&<div className="small-buttons"><button onClick={()=>conciliar(p.id,true)}>Banco confirmó</button><button className="secondary" onClick={()=>conciliar(p.id,false)}>No procesó</button></div>}</td></tr>)}</tbody></table></div></section>
  <section><h2>Auditoría</h2><div className="audit">{audit.slice(0,15).map(a=><div key={a.id}><strong>{a.accion}</strong><span>{a.resultado}</span><span>{a.detalle}</span><small>{new Date(a.fecha).toLocaleString()}</small></div>)}</div></section>
 </main>
}
ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
