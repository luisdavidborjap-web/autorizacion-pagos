insert into cuentas(numero,titular,saldo,limite_diario,usado_hoy,activa) values
('CTA-001','Ana Torres',5000,2000,0,true),
('CTA-002','Luis Herrera',12000,5000,0,true),
('CTA-003','María López',800,1000,0,true),
('CTA-004','Cuenta bloqueada',9000,3000,0,false)
on conflict (numero) do nothing;
