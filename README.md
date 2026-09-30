# Autorización de pagos

Aplicación académica para una cooperativa que recibe pagos desde una aplicación móvil.

## Diseño
- **Síncrono:** validar idempotencia, cuenta, saldo, límite diario y solicitar autorización al banco externo.
- **Segundo plano:** notificaciones y conciliación mediante RabbitMQ.
- **Duplicados:** cada solicitud usa `Idempotency-Key`, única en PostgreSQL. Un reintento devuelve el pago existente y no cobra otra vez.
- **Auditoría:** registra cada intento, validación, respuesta, fallo, timeout, reintento y conciliación.
- **Banco lento/no disponible:** el pago queda `PENDIENTE_CONCILIACION`; no se vuelve a cobrar automáticamente.

## Stack
Spring Boot, PostgreSQL, REST, OpenAPI/Swagger, RabbitMQ, React, Docker.

## Ejecutar
Con Docker Desktop abierto:

```bash
docker compose up --build
```

Abrir:
- Aplicación: http://localhost:8081
- Swagger: http://localhost:8081/swagger
- RabbitMQ: http://localhost:15673

Credenciales RabbitMQ de demostración:
- Usuario: `pagos`
- Contraseña: `pagos123`

## Pruebas importantes
1. **Pago normal:** Banco externo = `Responder OK`.
2. **Error externo:** Banco externo = `Fallar`; queda pendiente de conciliación.
3. **Timeout:** Banco externo = `No responder a tiempo`; queda pendiente de conciliación.
4. **Idempotencia:** enviar dos veces con la misma `Idempotency-Key`; el saldo solo se descuenta una vez.
5. **Conciliación:** en un pago pendiente, marcar si el banco confirmó o no procesó.

## Métricas
- Latencia promedio de autorización.
- Pagos duplicados evitados.
- Tasa de errores.
- Tiempo promedio de conciliación.
