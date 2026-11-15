package com.empresa.solicitudes.controller;

import com.empresa.solicitudes.entity.*;
import com.empresa.solicitudes.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import com.empresa.solicitudes.security.JwtCookieService;
import com.empresa.solicitudes.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

@Controller
public class PageController {

    @Autowired private IClienteService clienteService;
    @Autowired private IEmpleadoService empleadoService;
    @Autowired private ISolicitudService solicitudService;
    @Autowired private ITipoSolicitudService tipoSolicitudService;
    @Autowired private ICategoriaService categoriaService;
    @Autowired private AuthenticationManager authenticationManager;
    @Autowired private JwtService jwtService;
    @Autowired private JwtCookieService jwtCookieService;

    @GetMapping("/")
    public String index() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/registro")
    public String mostrarRegistro() {
        return "registro";
    }

    // Registro real: crea un Cliente de verdad con IClienteService (que ya
    // encripta la contraseña), y de una vez lo loguea para que caiga
    // directo en el menú de PQRSF, sin tener que volver a escribir sus datos.
    @PostMapping("/registro")
    public String registrar(@RequestParam String nombre,
                             @RequestParam String email,
                             @RequestParam(required = false) String telefono,
                             @RequestParam String contrasena,
                             Model model,
                             HttpServletRequest request,
                             HttpServletResponse response) {
        try {
            Cliente cliente = new Cliente();
            cliente.setNombre(nombre);
            cliente.setEmail(email);
            cliente.setTelefono(telefono);
            cliente.setContrasena(contrasena);
            cliente.setFechaRegistro(new Date());
            clienteService.guardar(cliente);

            // Auto-login: usamos la misma contraseña que acaba de escribir
            // y le entregamos su JWT en la cookie (ya no se usa la sesion).
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, contrasena));
            UserDetails user = (UserDetails) authentication.getPrincipal();
            jwtCookieService.guardarToken(response, jwtService.generarToken(user));

            return "redirect:/solicitudes/nueva";
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
            return "registro";
        }
    }

    
    @GetMapping("/pendiente")
    public String pendiente() {
        return "redirect:/login?sinPermiso";
    }

    
    @GetMapping("/acceso-denegado")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String accesoDenegado() {
        return "error/403";
    }

    
    @GetMapping("/dashboard")
    public String dashboard(Model model, Authentication authentication) {
        boolean esEmpleado = esEmpleado(authentication);

        model.addAttribute("email", authentication.getName());
        model.addAttribute("esEmpleado", esEmpleado);
        model.addAttribute("totalSolicitudes", solicitudService.listarTodas().size());

        if (esEmpleado) {
            model.addAttribute("totalClientes", clienteService.listarTodos().size());
            model.addAttribute("totalEmpleados", empleadoService.listarTodos().size());
        }

        return "dashboard";
    }

    // ─────────────────────────── MENÚ PQRSF (CLIENTE) ───────────────────────────

    @GetMapping("/solicitudes/nueva")
    public String nuevaSolicitudForm(Model model) {
        model.addAttribute("tipos", tipoSolicitudService.listarTodos());
        model.addAttribute("categorias", categoriaService.listar());
        return "nueva-solicitud";
    }

    @PostMapping("/solicitudes/nueva")
    public String crearSolicitud(@RequestParam String titulo,
                                  @RequestParam String tipoSolicitudId,
                                  @RequestParam(required = false) List<String> categoriaIds,
                                  @RequestParam String descripcion,
                                  @RequestParam(required = false) String prioridad,
                                  @RequestParam(required = false) String sede,
                                  @RequestParam(required = false) String zonaGimnasio,
                                  @RequestParam(required = false) String maquinaAfectada,
                                  Authentication authentication,
                                  Model model) {
        try {
            Cliente cliente = clienteService.buscarPorEmail(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("Cliente no encontrado"));

            Solicitud solicitud = new Solicitud();
            solicitud.setTitulo(titulo);
            solicitud.setDescripcion(descripcion);
            solicitud.setPrioridad((prioridad == null || prioridad.isBlank()) ? "Media" : prioridad);
            solicitud.setSede(sede);
            solicitud.setZonaGimnasio(zonaGimnasio);
            solicitud.setMaquinaAfectada(maquinaAfectada);
            solicitud.setCliente(cliente);
            
            solicitud.setTipoSolicitudId(tipoSolicitudId);
            if (categoriaIds != null) {
                solicitud.setCategoriaIds(categoriaIds);
            }

            solicitudService.guardar(solicitud);

            return "redirect:/solicitudes/mias?creada";
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("tipos", tipoSolicitudService.listarTodos());
            model.addAttribute("categorias", categoriaService.listar());
            return "nueva-solicitud";
        }
    }

    @GetMapping("/solicitudes/mias")
    public String misSolicitudes(Model model, Authentication authentication) {
        Cliente cliente = clienteService.buscarPorEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Cliente no encontrado"));
        model.addAttribute("solicitudes", solicitudService.buscarPorCliente(cliente.getId()));
        return "mis-solicitudes";
    }

    // ─────────────────────── GESTIÓN DE SOLICITUDES (EMPLEADO) ───────────────────────

    @GetMapping("/solicitudes/todas")
    public String todasSolicitudes(Model model) {
        model.addAttribute("solicitudes", solicitudService.listarTodas());
        return "admin-solicitudes";
    }

    @GetMapping("/solicitudes/{id}/gestionar")
    public String gestionarSolicitudForm(@PathVariable String id, Model model) {
        Solicitud s = solicitudService.buscarPorId(id)
                .orElseThrow(() -> new RuntimeException("Solicitud no encontrada"));
        model.addAttribute("solicitud", s);
        return "gestionar-solicitud";
    }

    @PostMapping("/solicitudes/{id}/gestionar")
    public String gestionarSolicitud(@PathVariable String id,
                                      @RequestParam String estado,
                                      @RequestParam(required = false) String respuestaAdmin) {
        Solicitud s = solicitudService.buscarPorId(id)
                .orElseThrow(() -> new RuntimeException("Solicitud no encontrada"));
        s.setEstado(estado);
        s.setRespuestaAdmin(respuestaAdmin);
        if (respuestaAdmin != null && !respuestaAdmin.isBlank()) {
            s.setFechaRespuesta(new Date());
        }
        solicitudService.guardar(s);
        return "redirect:/solicitudes/todas";
    }

    private boolean esEmpleado(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_EMPLEADO"));
    }

    // ───────────────────────── ADMINISTRACIÓN (solo ADMIN) ─────────────────────────

    @GetMapping("/admin/empleados")
    public String listarEmpleados(Model model) {
        model.addAttribute("empleados", empleadoService.listarTodos());
        return "admin-empleados";
    }

    @GetMapping("/admin/empleados/nuevo")
    public String nuevoEmpleadoForm() {
        return "nuevo-empleado";
    }

   
    @PostMapping("/admin/empleados/nuevo")
    public String crearEmpleado(@RequestParam String nombre,
                                 @RequestParam String email,
                                 @RequestParam String cargo,
                                 @RequestParam String contrasena,
                                 Model model) {
        try {
            Empleado empleado = new Empleado();
            empleado.setNombre(nombre);
            empleado.setEmail(email);
            empleado.setCargo(cargo);
            empleado.setContrasena(contrasena);
            empleado.setFechaContratacion(new Date());
            empleado.setHabilitado(cargo.trim().equalsIgnoreCase("Administrador"));
            empleadoService.guardar(empleado);
            return "redirect:/admin/empleados";
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
            return "nuevo-empleado";
        }
    }

    @PostMapping("/admin/empleados/{id}/habilitar")
    public String habilitarEmpleado(@PathVariable String id) {
        empleadoService.cambiarHabilitado(id, true);
        return "redirect:/admin/empleados";
    }

    @PostMapping("/admin/empleados/{id}/deshabilitar")
    public String deshabilitarEmpleado(@PathVariable String id) {
        empleadoService.cambiarHabilitado(id, false);
        return "redirect:/admin/empleados";
    }

    @PostMapping("/admin/empleados/{id}/eliminar")
    public String eliminarEmpleado(@PathVariable String id) {
        empleadoService.eliminar(id);
        return "redirect:/admin/empleados";
    }

    // Catálogos: tipos de PQRSF y categorías que alimentan el menú de "Nueva Solicitud"
    @GetMapping("/admin/catalogos")
    public String catalogos(Model model) {
        model.addAttribute("tipos", tipoSolicitudService.listarTodos());
        model.addAttribute("categorias", categoriaService.listar());
        return "admin-catalogos";
    }

    @PostMapping("/admin/catalogos/tipos")
    public String crearTipo(@RequestParam String nombre, @RequestParam(required = false) String descripcion) {
        TipoSolicitud tipo = new TipoSolicitud();
        tipo.setNombre(nombre);
        tipo.setDescripcion(descripcion);
        tipoSolicitudService.guardar(tipo);
        return "redirect:/admin/catalogos";
    }

    @PostMapping("/admin/catalogos/categorias")
    public String crearCategoria(@RequestParam String nombre, @RequestParam(required = false) String descripcion) {
        Categoria categoria = new Categoria();
        categoria.setNombre(nombre);
        categoria.setDescripcion(descripcion);
        categoriaService.guardar(categoria);
        return "redirect:/admin/catalogos";
    }

    @PostMapping("/admin/catalogos/tipos/{id}/editar")
    public String editarTipo(@PathVariable String id, @RequestParam String nombre,
                              @RequestParam(required = false) String descripcion) {
        TipoSolicitud tipo = new TipoSolicitud();
        tipo.setNombre(nombre);
        tipo.setDescripcion(descripcion);
        tipoSolicitudService.actualizar(id, tipo);
        return "redirect:/admin/catalogos";
    }

    @PostMapping("/admin/catalogos/tipos/{id}/eliminar")
    public String eliminarTipo(@PathVariable String id) {
        tipoSolicitudService.eliminar(id);
        return "redirect:/admin/catalogos";
    }

    @PostMapping("/admin/catalogos/categorias/{id}/editar")
    public String editarCategoria(@PathVariable String id, @RequestParam String nombre,
                                   @RequestParam(required = false) String descripcion) {
        Categoria categoria = new Categoria();
        categoria.setNombre(nombre);
        categoria.setDescripcion(descripcion);
        categoriaService.actualizar(id, categoria);
        return "redirect:/admin/catalogos";
    }

    @PostMapping("/admin/catalogos/categorias/{id}/eliminar")
    public String eliminarCategoria(@PathVariable String id) {
        categoriaService.eliminar(id);
        return "redirect:/admin/catalogos";
    }
}
