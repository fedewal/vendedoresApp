# Vendedores — app Android

Caparazón Android del módulo de vendedores de calle de ClearWater
(`/vendedores/` en el backend de Scrappy).

**No es un port.** Las pantallas siguen viviendo en Django y se ven en un
WebView: un cambio en el embudo o en la ficha del lead le llega al vendedor
recargando, sin publicar un APK nuevo. La app existe sólo para lo que un
navegador no puede hacer.

| | Chrome | esta app |
|---|---|---|
| Pantallas, login, llamar, WhatsApp | ✅ | ✅ (las mismas) |
| Ubicación con la app cerrada | ❌ | ✅ |
| Subir las grabaciones de llamadas | ❌ | ⚠ pendiente |
| Grabar las llamadas | ❌ | ❌ (ver abajo) |

## Instalación

Bajar el APK de [Releases](../../releases), abrirlo en el teléfono y
aceptar "instalar apps de orígenes desconocidos" cuando Android lo pida.

Después de instalar, **tres cosas que hay que hacer en cada teléfono** o el
seguimiento no funciona:

1. **Ubicación → Permitir siempre.** Android no la concede desde el diálogo
   de la app: hay que entrar a Ajustes → Aplicaciones → Vendedores →
   Permisos → Ubicación → "Permitir todo el tiempo".
2. **Batería sin restricciones.** En Samsung, Ajustes → Batería →
   Límites de uso en segundo plano → sacar la app de "Aplicaciones en
   suspensión", y en Ajustes → Aplicaciones → Vendedores → Batería →
   "Sin restricciones". One UI mata los servicios en segundo plano de forma
   agresiva y sin esto el rastreo se corta solo a las pocas horas.
3. **Notificaciones permitidas.** El servicio de ubicación es un servicio en
   primer plano y necesita su notificación; sin ella Android no lo deja
   correr.

## Qué hace

- **`MainActivity`** — el WebView. Mantiene la cookie de sesión entre
  aperturas, manda `tel:` y `whatsapp:` al sistema (si los abriera el
  WebView, el botón de Llamar no haría nada) y deja que "atrás" navegue
  dentro del sitio.
- **`UbicacionService`** — servicio en primer plano que toma **un punto cada
  10 s, mandados en tanda cada minuto** (`puntos` = JSON con `lat`, `lon`,
  `precision` y `t`, la hora del teléfono en milisegundos). Parado también
  toma puntos: es lo que deja ver cuánto estuvo en cada lugar. Se autentica
  con **la misma cookie de sesión del WebView**: no hay ninguna credencial
  dentro del APK, que es lo que permite que este repo sea público. Si la
  tanda no llega (sin señal, sesión vencida → 302), los puntos esperan a la
  siguiente, hasta 600 (100 minutos); el servidor descarta los repetidos.
- **`ArranqueReceiver`** — lo vuelve a levantar después de reiniciar el
  teléfono. Sin esto, un reinicio de madrugada deja al vendedor sin
  registrar y nadie se entera.

El backend lo recibe en `POST /vendedores/ubicacion/` y lo guarda en
`UbicacionVendedor` (app `vendedores` de `scrapp-test`).

## Sobre grabar llamadas

**Esta app no graba llamadas, y no es una omisión.** Desde Android 10,
capturar el audio de una llamada exige `CAPTURE_AUDIO_OUTPUT`, que es un
permiso de firma: sólo lo tienen las apps firmadas con el certificado de la
plataforma o el discador que viene de fábrica. No hay permiso que el usuario
pueda conceder para habilitarlo.

Los caminos que sí existen, en orden de esfuerzo:

1. **Un grabador de terceros** (TalkerACR, Cube ACR) escribe los `.m4a` en
   almacenamiento compartido y esta app los sube. Verificado que funciona en
   un Samsung con TalkerACR, que deja los archivos en
   `/storage/emulated/0/Documents/TalkerACR/`. **Depende del modelo**: en
   algunos equipos sólo se graba el lado local.
2. **Grabarlo nosotros** por el mismo camino que usan esas apps (servicio de
   accesibilidad + `MediaRecorder`). Es posible, pero cuál fuente de audio
   captura al interlocutor depende del fabricante y de la versión: hay que
   probarlo en el equipo concreto y queda frágil ante una actualización.
3. **VoIP** — las llamadas salen por una capa propia y la grabación es del
   lado del servidor. No depende del modelo y anda en cualquier teléfono,
   pero es un proyecto de telefonía aparte.

Nada de esto alcanza a las llamadas de WhatsApp ni a las hechas fuera de la
app: lo que pasa por el discador del sistema es del sistema.

## Compilar

Necesita el SDK de Android y un JDK 17+ (sirve el que trae Android Studio).

```bash
echo "sdk.dir=C:/ruta/al/Android/Sdk" > local.properties
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

**Cero dependencias**, y es a propósito: con `minSdk 26` todo lo que usa la
app está en la plataforma. Se probó con `appcompat` y arrastraba la stdlib de
Kotlin en dos versiones incompatibles; sacarla resolvió eso de raíz y dejó el
APK en 15 KB.

Para firmar una release hace falta un `keystore.properties` en la raíz (no
está en el repo):

```properties
storeFile=C:/ruta/al/vendedores-release.jks
storePassword=...
keyAlias=vendedores
keyPassword=...
```

⚠ **Si se pierde el keystore, la app no se puede volver a actualizar nunca**:
Android rechaza una actualización firmada con otra clave y hay que
desinstalar y reinstalar en cada teléfono.

## Privacidad

La app registra dónde está el vendedor mientras el servicio corre. Es dato
personal del trabajador (ley 18.331): se guarda lo mínimo —punto, precisión y
momento—, sobre equipos de la empresa, y la notificación permanente está a
propósito para que el vendedor vea que se lo está registrando.
