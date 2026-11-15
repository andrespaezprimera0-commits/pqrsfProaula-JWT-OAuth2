package com.empresa.solicitudes.exception;

/** La foto de evidencia no cumple las reglas (vacía, muy pesada, formato no permitido...). Responde 400. */
public class EvidenciaInvalidaException extends RuntimeException {
    public EvidenciaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
