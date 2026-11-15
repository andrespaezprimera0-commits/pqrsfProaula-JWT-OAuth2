package com.empresa.solicitudes.service;

import com.empresa.solicitudes.entity.Solicitud;
import org.springframework.web.multipart.MultipartFile;

/**
 * Fotos de evidencia de una solicitud (ej: la máquina dañada, el baño sucio).
 * Las imágenes se guardan en MongoDB GridFS.
 */
public interface IEvidenciaService {

    /** Guarda la foto en GridFS y agrega su id a la solicitud. Devuelve el id de la foto. */
    String agregarEvidencia(String solicitudId, MultipartFile foto);

    /** Devuelve la imagen (bytes + tipo) si pertenece a esa solicitud. */
    ArchivoEvidencia obtenerEvidencia(String solicitudId, String evidenciaId);

    /** Borra de GridFS todas las fotos de una solicitud (se usa al eliminarla). */
    void eliminarEvidenciasDeSolicitud(Solicitud solicitud);

    /** Imagen leída de GridFS. */
    record ArchivoEvidencia(byte[] contenido, String contentType, String nombre) {}
}
