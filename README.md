# BastionMCAuth 0.1.0-beta

Plugin **experimental** de autenticación para **Purpur 1.21.11 / Java 21**. Login obligatorio para todos: `/register <contraseña> <contraseña>` y `/login <contraseña>`.

## Compilar

Subir todo el contenido a la raíz de GitHub. Ir a **Actions → Build BastionMCAuth**. El workflow utiliza Gradle y entrega el JAR en **Artifacts**. No se incluye Gradle Wrapper: la compilación depende de Gradle disponible en el runner.

## Instalar

Copiar el JAR a `plugins/` y reiniciar. Configurar `plugins/BastionMCAuth/config.yml` y reiniciar tras cambios. Guardar respaldos de `accounts.db` con el servidor detenido.

## Seguridad y limitaciones importantes

**BETA NO AUDITADA: NO RECOMENDADA PARA UN SERVIDOR PÚBLICO.** `online-mode=false` no autentica cuentas Microsoft. Las cuentas se identifican por nombre, por lo que cambiar el nombre puede crear una cuenta distinta. El bloqueo de acciones no cubre necesariamente todos los eventos y APIs de otros plugins, ni protege contra plugins maliciosos o vulnerabilidades del servidor. Las contraseñas se introducen en comandos y podrían filtrarse mediante clientes, registros, proxies o plugins de monitoreo. El límite de intentos es por conexión, no persiste entre reconexiones, y **no** es protección suficiente contra fuerza bruta distribuida. No se han implementado recuperación de cuenta, 2FA, migración ni protección contra bots. Para uso público, utilizar una solución madura y auditada como AuthMe Reloaded con configuración de red apropiada.

La base de datos usa bcrypt con sal individual y coste configurable. Se almacenan hashes, no contraseñas en claro. La configuración admite códigos `&` y `&#RRGGBB`.

## Funciones

- Registro e inicio de sesión obligatorios; sin sesiones automáticas.
- Congelación, bloqueo de comandos salvo `/login` y `/register`, chat, inventario, interacción, daño y recogida de objetos.
- Título persistente con cuenta regresiva, oscuridad y lentitud, retiradas al autenticarse.
- SQLite y mensajes YAML.

Pruebe en un servidor de pruebas antes de desplegar.


## Spawn al entrar (v0.1.1-beta)

1. Instala el nuevo JAR y reinicia el servidor.
2. Inicia sesion como OP, ve al spawn deseado y ejecuta `/authsetspawn`.
3. Se guardara `spawn.teleport-on-join: true` y la ubicacion en `config.yml`.
4. Al reconectar, todos los jugadores apareceran en ese spawn y necesitaran `/login` o `/register`.
5. Para desactivar, cambia `spawn.teleport-on-join: false` y reinicia.

El spawn de BastionMCAuth es independiente del de BastionMCSpawn. Configuralo en el mismo lugar.
No modifica las cuentas almacenadas en `accounts.db`. No borres ese archivo al actualizar.
