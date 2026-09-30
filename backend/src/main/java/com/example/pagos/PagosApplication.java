package com.example.pagos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PagosApplication {
  public static void main(String[] args) { SpringApplication.run(PagosApplication.class, args); }
  @Bean Queue conciliacionQueue() { return new Queue("pagos.conciliacion", true); }
  @Bean Queue notificacionesQueue() { return new Queue("pagos.notificaciones", true); }
}
