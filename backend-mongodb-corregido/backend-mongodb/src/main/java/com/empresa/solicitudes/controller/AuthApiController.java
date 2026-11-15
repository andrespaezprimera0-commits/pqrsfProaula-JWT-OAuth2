package com.empresa.solicitudes.controller;

import com.empresa.solicitudes.dto.LoginResponse;
import com.empresa.solicitudes.entity.Cliente;
import com.empresa.solicitudes.entity.Empleado;
import com.empresa.solicitudes.repository.ClienteRepository;
import com.empresa.solicitudes.repository.EmpleadoRepository;
import com.empresa.solicitudes.security.JwtService;
import com.empresa.solicitudes.security.RedireccionPorRol;
import com.empresa.solicitudes.security.UsuarioDetailsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Login por API (antes se llamaba AuthController).
 *
 * POST /api/auth/login   {"email": "...", "password": "..."}
 *   -> devuelve LoginResponse con el JWT.
 * POST /api/auth/google  {"idToken": "..."}  (login con Google desde la app Flutter)
 *   -> verifica el idToken con Google y devuelve el mismo LoginResponse con el JWT.
 * GET  /api/auth/me      (con Authorization: Bearer <token>)
 *   -> devuelve quien es el usuario del token (sirve para probar).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthApiController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final EmpleadoRepository empleadoRepository;
    private final ClienteRepository clienteRepository;
    private final UsuarioDetailsService usuarioDetailsService;
    private final PasswordEncoder passwordEncoder;

    // El mismo client-id de Google que usa la web (no es secreto).
    // El idToken que manda la app debe haber sido emitido para ESTE client-id.
    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String googleClientId;

    private final RestTemplate restTemplate = new RestTemplate();

    public AuthApiController(AuthenticationManager authenticationManager,
                             JwtService jwtService,
                             EmpleadoRepository empleadoRepository,
                             ClienteRepository clienteRepository,
                             UsuarioDetailsService usuarioDetailsService,
                             PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.empleadoRepository = empleadoRepository;
        this.clienteRepository = clienteRepository;
        this.usuarioDetailsService = usuarioDetailsService;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String contrasena = body.get("password");

        if (email == null || contrasena == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Envia email y password."));
        }

        // AuthenticationManager -> UsuarioDetailsService (MongoDB) + BCrypt
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, contrasena));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Correo o contraseña incorrectos."));
        }

        UserDetails user = (UserDetails) authentication.getPrincipal();
        if (RedireccionPorRol.esPendiente(user)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error",
                    "No puedes ingresar porque tu cuenta no tiene permiso. Contacta al administrador."));
        }

        return ResponseEntity.ok(respuestaLogin(user, email));
    }

    /**
     * Login con Google desde la app Flutter.
     * 1. La app inicia sesion con Google y recibe un idToken (firmado por Google).
     * 2. Aqui se le pregunta a Google si ese idToken es valido (endpoint tokeninfo).
     * 3. Se revisa que sea para NUESTRO client-id y que el correo este verificado.
     * 4. Igual que en la web: si el correo no existe se crea como CLIENTE.
     * 5. Se devuelve nuestro propio JWT, igual que /api/auth/login.
     */
    @PostMapping("/google")
    public ResponseEntity<?> loginGoogle(@RequestBody Map<String, String> body) {
        String idToken = body.get("idToken");
        if (idToken == null || idToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Falta el idToken de Google."));
        }

        Map<?, ?> info;
        try {
            info = restTemplate.getForObject(
                    "https://oauth2.googleapis.com/tokeninfo?id_token={t}", Map.class, idToken);
        } catch (RestClientException e) {
            // Google responde 400 si el token es falso, esta vencido o mal formado
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "El inicio de sesion con Google no es valido. Intenta de nuevo."));
        }

        boolean paraNosotros = info != null && googleClientId.equals(info.get("aud"));
        boolean emisorGoogle = info != null
                && Set.of("accounts.google.com", "https://accounts.google.com").contains(String.valueOf(info.get("iss")));
        boolean verificado = info != null && "true".equals(String.valueOf(info.get("email_verified")));
        String email = info != null ? (String) info.get("email") : null;

        if (!paraNosotros || !emisorGoogle || !verificado || email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "No se pudo verificar tu cuenta de Google."));
        }

        // Misma regla que OAuth2AuthenticationSuccessHandler (login con Google en la web)
        if (!empleadoRepository.existsByEmail(email) && !clienteRepository.existsByEmail(email)) {
            Cliente nuevo = new Cliente();
            Object nombre = info.get("name");
            nuevo.setNombre(nombre != null ? nombre.toString() : email);
            nuevo.setEmail(email);
            nuevo.setFechaRegistro(new Date());
            nuevo.setContrasena(passwordEncoder.encode(UUID.randomUUID().toString()));
            clienteRepository.save(nuevo);
        }

        UserDetails user = usuarioDetailsService.loadUserByUsername(email);
        if (RedireccionPorRol.esPendiente(user)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error",
                    "No puedes ingresar porque tu cuenta no tiene permiso. Contacta al administrador."));
        }
        return ResponseEntity.ok(respuestaLogin(user, email));
    }

    /** Arma el LoginResponse (lo usan el login normal y el de Google). */
    private LoginResponse respuestaLogin(UserDetails user, String email) {
        String token = jwtService.generarToken(user);
        List<String> roles = user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();

        Empleado emp = empleadoRepository.findByEmail(email).orElse(null);
        if (emp != null) {
            return new LoginResponse(token, "Bearer", jwtService.getExpirationMs(),
                    "EMPLEADO", emp.getId(), emp.getNombre(), emp.getEmail(), roles);
        }
        Cliente cli = clienteRepository.findByEmail(email).orElseThrow();
        return new LoginResponse(token, "Bearer", jwtService.getExpirationMs(),
                "CLIENTE", cli.getId(), cli.getNombre(), cli.getEmail(), roles);
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        return Map.of(
                "email", authentication.getName(),
                "roles", authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
    }
}
