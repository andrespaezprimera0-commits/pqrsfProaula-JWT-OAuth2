package com.empresa.solicitudes.security;

import com.empresa.solicitudes.entity.Cliente;
import com.empresa.solicitudes.repository.ClienteRepository;
import com.empresa.solicitudes.repository.EmpleadoRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;
import java.util.UUID;


@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final EmpleadoRepository empleadoRepository;
    private final ClienteRepository clienteRepository;
    private final UsuarioDetailsService usuarioDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtCookieService cookieService;

    public OAuth2AuthenticationSuccessHandler(EmpleadoRepository empleadoRepository,
                                              ClienteRepository clienteRepository,
                                              UsuarioDetailsService usuarioDetailsService,
                                              PasswordEncoder passwordEncoder,
                                              JwtService jwtService,
                                              JwtCookieService cookieService) {
        this.empleadoRepository = empleadoRepository;
        this.clienteRepository = clienteRepository;
        this.usuarioDetailsService = usuarioDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.cookieService = cookieService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        OAuth2User googleUser = (OAuth2User) authentication.getPrincipal();
        String email = googleUser.getAttribute("email");
        String nombre = googleUser.getAttribute("name");
        Boolean verificado = googleUser.getAttribute("email_verified");

        if (email == null || Boolean.FALSE.equals(verificado)) {
            response.sendRedirect("/login?errorGoogle");
            return;
        }

        boolean existe = empleadoRepository.existsByEmail(email) || clienteRepository.existsByEmail(email);
        if (!existe) {
            crearCliente(email, nombre);
        }

        UserDetails user = usuarioDetailsService.loadUserByUsername(email);

        if (RedireccionPorRol.esPendiente(user)) {
            response.sendRedirect("/login?sinPermiso");
            return;
        }

        cookieService.guardarToken(response, jwtService.generarToken(user));
        response.sendRedirect(RedireccionPorRol.urlInicio(user));
    }

    private void crearCliente(String email, String nombre) {
        Cliente cliente = new Cliente();
        cliente.setNombre(nombre != null ? nombre : email);
        cliente.setEmail(email);
        cliente.setFechaRegistro(new Date());
        cliente.setContrasena(passwordEncoder.encode(UUID.randomUUID().toString()));
        clienteRepository.save(cliente);
    }
}
