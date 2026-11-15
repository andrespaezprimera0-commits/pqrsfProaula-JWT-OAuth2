package com.empresa.solicitudes.controller;

import com.empresa.solicitudes.entity.Cliente;
import com.empresa.solicitudes.entity.Solicitud;
import com.empresa.solicitudes.repository.ClienteRepository;
import com.empresa.solicitudes.service.IEvidenciaService;
import com.empresa.solicitudes.service.ISolicitudService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Fotos de evidencia de una PQRSF (requisito nuevo del profe para la app Flutter).
 *
 * POST /api/solicitudes/{id}/evidencias                  (multipart, campo "foto")
 *      -> sube una foto y devuelve la solicitud con su lista evidenciaIds.
 * GET  /api/solicitudes/{id}/evidencias/{evidenciaId}
 *      -> devuelve la imagen (para Image.network en Flutter o <img> en la web).
 *
 * Ambos exigen el JWT. Un CLIENTE solo puede tocar las fotos de SUS solicitudes;
 * un EMPLEADO/ADMIN puede ver las de cualquiera (para gestionarlas).
 */
@RestController
@RequestMapping("/api/solicitudes/{id}/evidencias")
public class EvidenciaController {

    private final IEvidenciaService evidenciaService;
    private final ISolicitudService solicitudService;
    private final ClienteRepository clienteRepository;

    public EvidenciaController(IEvidenciaService evidenciaService,
                               ISolicitudService solicitudService,
                               ClienteRepository clienteRepository) {
        this.evidenciaService = evidenciaService;
        this.solicitudService = solicitudService;
        this.clienteRepository = clienteRepository;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> subir(@PathVariable String id,
                                   @RequestParam("foto") MultipartFile foto,
                                   Authentication authentication) {
        Solicitud solicitud = solicitudService.buscarPorId(id)
                .orElseThrow(() -> new RuntimeException("Solicitud no encontrada"));

        // Solo el cliente dueño de la solicitud adjunta evidencia
        if (!esDueno(solicitud, authentication)) {
            return prohibido();
        }

        evidenciaService.agregarEvidencia(id, foto);
        return ResponseEntity.status(HttpStatus.CREATED).body(solicitudService.buscarPorId(id).orElseThrow());
    }

    @GetMapping("/{evidenciaId}")
    public ResponseEntity<?> ver(@PathVariable String id,
                                 @PathVariable String evidenciaId,
                                 Authentication authentication) {
        Solicitud solicitud = solicitudService.buscarPorId(id)
                .orElseThrow(() -> new RuntimeException("Solicitud no encontrada"));

        if (!esEmpleado(authentication) && !esDueno(solicitud, authentication)) {
            return prohibido();
        }

        IEvidenciaService.ArchivoEvidencia foto = evidenciaService.obtenerEvidencia(id, evidenciaId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(foto.contentType()))
                // La foto nunca cambia: el celular puede guardarla en caché 1 hora
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(foto.contenido());
    }

    private boolean esDueno(Solicitud solicitud, Authentication authentication) {
        if (solicitud.getClienteId() == null) {
            return false;
        }
        return clienteRepository.findById(solicitud.getClienteId())
                .map(Cliente::getEmail)
                .map(email -> email.equalsIgnoreCase(authentication.getName()))
                .orElse(false);
    }

    private boolean esEmpleado(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_EMPLEADO") || a.equals("ROLE_ADMIN"));
    }

    private ResponseEntity<?> prohibido() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "status", 403,
                "error", "Sin permiso",
                "mensaje", "Solo puedes ver o adjuntar evidencias de tus propias solicitudes."));
    }
}
