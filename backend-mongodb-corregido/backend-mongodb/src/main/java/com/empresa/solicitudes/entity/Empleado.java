package com.empresa.solicitudes.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * Colección "empleados".
 */
@Document(collection = "empleados")
public class Empleado {

    @Id
    private String id;

    private String nombre;

    @Indexed(unique = true)
    private String email;

    private String cargo;

    private Date fechaContratacion;

    private String contrasena;

    // Un empleado nuevo arranca deshabilitado (sin permisos) hasta que un
    // ADMIN lo habilite explícitamente. El único que se salta esta regla es
    // el que tiene cargo = "Administrador" (ver UsuarioDetailsService), para
    // que siempre haya alguien que pueda habilitar a los demás.
    private Boolean habilitado = false;

    public Empleado() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getCargo() { return cargo; }
    public void setCargo(String cargo) { this.cargo = cargo; }

    public Date getFechaContratacion() { return fechaContratacion; }
    public void setFechaContratacion(Date fechaContratacion) { this.fechaContratacion = fechaContratacion; }

    public String getContrasena() { return contrasena; }
    public void setContrasena(String contrasena) { this.contrasena = contrasena; }

    public Boolean getHabilitado() { return habilitado; }
    public void setHabilitado(Boolean habilitado) { this.habilitado = habilitado; }
}
