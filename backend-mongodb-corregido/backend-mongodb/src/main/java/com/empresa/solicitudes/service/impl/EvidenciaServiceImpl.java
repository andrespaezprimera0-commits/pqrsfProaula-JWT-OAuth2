package com.empresa.solicitudes.service.impl;

import com.empresa.solicitudes.entity.Solicitud;
import com.empresa.solicitudes.exception.EvidenciaInvalidaException;
import com.empresa.solicitudes.repository.SolicitudRepository;
import com.empresa.solicitudes.service.IEvidenciaService;
import com.mongodb.client.gridfs.model.GridFSFile;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@Service
public class EvidenciaServiceImpl implements IEvidenciaService {

    /** Máximo de fotos por solicitud. */
    public static final int MAX_FOTOS = 3;

    /** Tamaño máximo por foto: 5 MB (también limitado en application.properties). */
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    // Formatos permitidos (lo que mandan la cámara y la galería del celular)
    private static final Map<String, String> TIPOS_PERMITIDOS = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp",
            "heic", "image/heic");

    private final GridFsTemplate gridFsTemplate;
    private final SolicitudRepository solicitudRepository;

    public EvidenciaServiceImpl(GridFsTemplate gridFsTemplate,
                                SolicitudRepository solicitudRepository) {
        this.gridFsTemplate = gridFsTemplate;
        this.solicitudRepository = solicitudRepository;
    }

    @Override
    public String agregarEvidencia(String solicitudId, MultipartFile foto) {
        Solicitud solicitud = solicitudRepository.findById(solicitudId)
                .orElseThrow(() -> new RuntimeException("Solicitud no encontrada"));

        if (foto == null || foto.isEmpty()) {
            throw new EvidenciaInvalidaException("Debes adjuntar una foto.");
        }
        if (foto.getSize() > MAX_BYTES) {
            throw new EvidenciaInvalidaException("La foto supera el tamaño máximo de 5 MB.");
        }
        if (solicitud.getEvidenciaIds() == null) {
            solicitud.setEvidenciaIds(new ArrayList<>());
        }
        if (solicitud.getEvidenciaIds().size() >= MAX_FOTOS) {
            throw new EvidenciaInvalidaException("Solo se permiten " + MAX_FOTOS + " fotos por solicitud.");
        }

        String contentType = tipoDeImagen(foto);
        String nombre = foto.getOriginalFilename() != null ? foto.getOriginalFilename() : "evidencia";

        // Datos extra que quedan en fs.files para saber de qué solicitud es la foto
        Document metadata = new Document()
                .append("solicitudId", solicitudId)
                .append("fechaSubida", new Date());

        ObjectId fotoId;
        try (InputStream in = foto.getInputStream()) {
            fotoId = gridFsTemplate.store(in, nombre, contentType, metadata);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo guardar la foto.");
        }

        solicitud.getEvidenciaIds().add(fotoId.toHexString());
        solicitudRepository.save(solicitud);

        return fotoId.toHexString();
    }

    @Override
    public ArchivoEvidencia obtenerEvidencia(String solicitudId, String evidenciaId) {
        Solicitud solicitud = solicitudRepository.findById(solicitudId)
                .orElseThrow(() -> new RuntimeException("Solicitud no encontrada"));

        // Solo se entrega la foto si de verdad pertenece a esa solicitud
        if (solicitud.getEvidenciaIds() == null || !solicitud.getEvidenciaIds().contains(evidenciaId)
                || !ObjectId.isValid(evidenciaId)) {
            throw new RuntimeException("Evidencia no encontrada");
        }

        GridFSFile archivo = gridFsTemplate.findOne(query(where("_id").is(new ObjectId(evidenciaId))));
        if (archivo == null) {
            throw new RuntimeException("Evidencia no encontrada");
        }

        GridFsResource recurso = gridFsTemplate.getResource(archivo);
        try (InputStream in = recurso.getInputStream()) {
            String tipo = recurso.getContentType() != null ? recurso.getContentType() : "image/jpeg";
            return new ArchivoEvidencia(in.readAllBytes(), tipo, archivo.getFilename());
        } catch (IOException e) {
            throw new RuntimeException("No se pudo leer la foto.");
        }
    }

    @Override
    public void eliminarEvidenciasDeSolicitud(Solicitud solicitud) {
        if (solicitud.getEvidenciaIds() == null) {
            return;
        }
        for (String id : solicitud.getEvidenciaIds()) {
            if (ObjectId.isValid(id)) {
                gridFsTemplate.delete(query(where("_id").is(new ObjectId(id))));
            }
        }
    }

    /**
     * Decide el tipo de la imagen. Primero mira el Content-Type que manda el
     * celular; si no viene (algunos envían application/octet-stream), usa la
     * extensión del archivo. Si no es una imagen permitida, se rechaza.
     */
    private String tipoDeImagen(MultipartFile foto) {
        String contentType = foto.getContentType();
        if (contentType != null && TIPOS_PERMITIDOS.containsValue(contentType.toLowerCase())) {
            return contentType.toLowerCase();
        }
        String nombre = foto.getOriginalFilename();
        if (nombre != null && nombre.contains(".")) {
            String ext = nombre.substring(nombre.lastIndexOf('.') + 1).toLowerCase();
            if (TIPOS_PERMITIDOS.containsKey(ext)) {
                return TIPOS_PERMITIDOS.get(ext);
            }
        }
        throw new EvidenciaInvalidaException("Solo se permiten imágenes JPG, PNG, WEBP o HEIC.");
    }
}
