create table if not exists cuentas (
  numero varchar(30) primary key,
  titular varchar(120) not null,
  saldo numeric(14,2) not null,
  limite_diario numeric(14,2) not null,
  usado_hoy numeric(14,2) not null default 0,
  activa boolean not null default true
);

create table if not exists pagos (
  id bigserial primary key,
  idempotency_key varchar(120) not null unique,
  cuenta_origen varchar(30) not null,
  beneficiario varchar(120) not null,
  monto numeric(14,2) not null,
  estado varchar(40) not null,
  mensaje varchar(500),
  referencia_banco varchar(120),
  creado_en timestamp not null default now(),
  actualizado_en timestamp not null default now(),
  latencia_ms bigint,
  conciliado_en timestamp
);

create table if not exists auditoria (
  id bigserial primary key,
  pago_id bigint,
  idempotency_key varchar(120),
  accion varchar(80) not null,
  resultado varchar(80) not null,
  detalle varchar(1000),
  fecha timestamp not null default now()
);
