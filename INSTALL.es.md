# Instalar Lyra

Guía en lenguaje sencillo para quien no programa. English: [INSTALL.md](INSTALL.md).

Lyra es una pantalla de inicio propia para televisores y reproductores. No está en ninguna tienda: tú instalas ("sideload") un archivo APK. Es gratis, de código abierto (GPL-3.0) y no trae anuncios ni cuentas.

> Resumen honesto: Lyra está hecha y probada solo en un **Fire TV Stick 4K Max con Fire OS 7**. Otros modelos de Fire TV y los aparatos Android TV / Google TV deberían poder instalarla y abrirla, pero nadie los ha probado. Amazon no deja reemplazar su pantalla de inicio, así que en Fire TV Lyra se abre *encima* del inicio de Amazon (ver el [paso 4](#4-ponerla-como-pantalla-de-inicio)).

## 1. Qué necesitas

- **Un aparato de TV:** un Fire TV (Fire TV Stick 4K, 4K Max, Fire TV Cube o cualquier Fire TV con Fire OS 7 o superior) o un Android TV / Google TV. El mínimo es **Android 9 (API 28)**, que equivale a Fire OS 7. Los aparatos más viejos no la instalan.
- **Un control remoto.** El normal sirve: la app se maneja con las flechas y no necesita pantalla táctil.
- **Wi-Fi / internet** en la tele.
- **Un PC o celular** solo para las rutas B y C y para algunas funciones opcionales. La ruta A funciona solo con la tele.
- **Una clave gratuita de TMDB** (recomendada, ver el [paso 5](#5-poner-tus-claves)). Sin ella Lyra muestra solo filas de ejemplo.
- Opcional: un token de AniList, tu propio servidor de eventos y una app de IPTV. **Lyra no reproduce IPTV ni lee listas de canales.** Solo puede abrir una app de IPTV que ya tengas y escribir un título en su buscador (el paquete de esa app se pone en el código, ver el README).

## 2. Elige cómo instalar

### Ruta A: sin PC, con la app Downloader (la más fácil)

1. En la tele instala **Downloader** (de AFTVnews) desde la tienda de Amazon (Fire TV) o Google Play (Android TV).
2. Permítele instalar apps:
   - **Fire TV:** *Configuración > Mi Fire TV > Opciones para desarrolladores > Instalar apps desconocidas > Downloader: Sí*. Si no ves *Opciones para desarrolladores*, entra a *Configuración > Mi Fire TV > Acerca de*, selecciona el nombre del aparato y pulsa OK 7 veces.
   - **Android TV / Google TV:** *Configuración > Apps > Seguridad y restricciones > Orígenes desconocidos > Downloader* (la ruta cambia según el aparato).
3. Abre Downloader, selecciona la caja de dirección y escribe exactamente:

   `https://github.com/Danvegamo/lyra-tv-launcher/releases/latest/download/lyra-tv-launcher.apk`

4. Pulsa *Ir*, espera la descarga, elige **Instalar** y luego **Abrir** (o busca **Lyra** después en tus apps).
5. Borra el APK descargado cuando Downloader te lo ofrezca; ya no lo necesitas.

El APK publicado no trae ninguna clave, así que muestra filas de ejemplo hasta que pongas la tuya (paso 5). Va firmado con una clave de depuración: Android lo acepta, pero un APK que compiles tú va firmado con otra clave y no se instala encima (desinstala primero).

### Ruta B: desde un PC con adb

1. En la tele: *Configuración > Mi Fire TV > Opciones para desarrolladores > Depuración ADB: Sí*. Anota la IP: *Configuración > Mi Fire TV > Acerca de > Red*.
2. En el PC instala **adb** (Android platform-tools: <https://developer.android.com/tools/releases/platform-tools>). El PC y la tele deben estar en la misma red.
3. Descarga `lyra-tv-launcher-<versión>.apk` de la [última versión](https://github.com/Danvegamo/lyra-tv-launcher/releases/latest) y ejecuta:

   ```sh
   adb connect <ip-de-la-tele>:5555
   adb install -r lyra-tv-launcher-<versión>.apk
   ```

   La primera vez la tele te pide permitir el PC: elige *Permitir siempre*.
4. En Windows puedes usar el script, que además activa el servicio opcional del botón Home: `.\instalar.ps1 -Tele <ip-de-la-tele> -Apk lyra-tv-launcher-<versión>.apk`.

### Ruta C: compilarla tú

Necesitas **JDK 17 o superior** (recomendado 21), el **Android SDK** (Android Studio, o solo las herramientas de línea de comandos con la plataforma 34) y **git**.

```sh
git clone https://github.com/Danvegamo/lyra-tv-launcher.git
cd lyra-tv-launcher
cp config.example.properties config.local.properties     # Windows: Copy-Item
# edita config.local.properties y pon tus claves (paso 5)
./gradlew assembleDebug          # Windows: .\gradlew.bat assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`. `./gradlew assembleRelease` da un APK más pequeño (`app/build/outputs/apk/release/app-release.apk`); también va firmado con la clave de depuración, así que es para tu propia tele. Instálalo con la ruta A (déjalo en un sitio desde el que puedas descargarlo) o con la B. Si Gradle no encuentra el SDK, define `ANDROID_HOME` o crea `local.properties` con `sdk.dir=<ruta del Android SDK>`. Un APK compilado con tus claves las lleva dentro: no lo compartas.

## 3. Comprueba que abre

Abre **Lyra** desde tu lista de apps. Debes ver la intro de Lyra y luego la pantalla de inicio con filas. Muévete con las flechas, OK abre un título y la tecla de menú (las tres rayas) abre el menú del título: tráiler, marcar como visto, no recomendar, Mi lista. Dentro de Lyra, *Ajustes > Estado del sistema* dice qué funciona y cómo arreglar lo que no.

## 4. Ponerla como pantalla de inicio

Depende del aparato y es la parte menos probada.

**Android TV / Google TV.** Lyra se declara como posible app de inicio. Que puedas elegirla depende del fabricante: algunos Android TV dejan escoger la app de inicio predeterminada (en *Configuración > Apps > Apps predeterminadas*, o preguntando qué inicio usar la primera vez que pulsas Home) y muchos Google TV no lo permiten. Si tu aparato lo ofrece, elige Lyra. Si no, Lyra sigue funcionando como una app normal que abres desde la lista. Lo específico de Fire de abajo no aplica.

**Fire TV (Fire OS).** Amazon no permite cambiar la pantalla de inicio y Lyra no usa ningún exploit ni root. En su lugar ofrece un **servicio de accesibilidad opcional** («Lyra: botón Home»): cuando aparece el inicio de Amazon, el servicio abre Lyra encima. Solo mira cambios de ventana de una lista corta de apps (el launcher de Amazon, tu app de IPTV, Crunchyroll, HBO Max y el teclado de Amazon) y solo lee el contenido de la pantalla mientras corre una búsqueda guiada. Límites honestos:

- Puede verse el inicio de Amazon un momento antes de que aparezca Lyra (unos segundos en el aparato probado; Lyra pone su intro para tapar ese hueco).
- Una actualización de Fire OS puede romperlo.
- «Forzar detención» sobre Lyra hace que Fire OS apague el servicio.

Para activar el servicio:

- **Con el script (la forma probada, Windows):** `.\instalar.ps1 -Tele <ip-de-la-tele>`. Instala el APK y activa el servicio.
- **A mano con adb (cualquier sistema):**

  ```sh
  adb shell settings put secure enabled_accessibility_services co.davidvega.launcher/co.davidvega.launcher.sistema.HomeAccessibilityService
  adb shell settings put secure accessibility_enabled 1
  adb shell pm grant co.davidvega.launcher android.permission.WRITE_SECURE_SETTINGS
  ```

  Las dos primeras líneas cambian ajustes seguros de la tele (el paso de desinstalar las deshace). La última deja que Lyra vuelva a activar sola el servicio, y la escucha de «lo que se reproduce», tras un reinicio o una actualización.
- **Sin PC:** el servicio debería aparecer en *Configuración > Accesibilidad* como «Lyra: botón Home»; actívalo ahí. Esto no se ha probado en un aparato real y Fire OS puede ocultarlo o reiniciarlo. Si no funciona, usa adb.

Extras opcionales (cada uno necesita adb una vez):

- Ordenar tus apps por uso: `adb shell appops set co.davidvega.launcher GET_USAGE_STATS allow`.
- «Seguir viendo» y lo que se reproduce vienen de un servicio de escucha de notificaciones («Lyra: lo que se reproduce»). Con el permiso `WRITE_SECURE_SETTINGS` de arriba, Lyra lo activa sola en el siguiente reinicio o actualización.
- Mantenimiento nocturno (apagado salvo que lo autorices): *Ajustes > Autorizar mantenimiento* y acepta «Permitir siempre» en el aviso de adb. A las 4:30, si nadie ve la tele, recorta cachés y reinicia la tele por adb local. Se apaga en *Ajustes > Mantenimiento nocturno*. Necesita que la depuración ADB por red siga activa.

## 5. Poner tus claves

Lyra necesita datos de internet. Todas las claves son **tuyas**; la app no trae ninguna. La plantilla es [`config.example.properties`](config.example.properties), con un comentario en cada línea.

| Clave | ¿Hace falta? | Cómo conseguirla |
| --- | --- | --- |
| `TMDB_API_KEY` o `TMDB_READ_TOKEN` | Recomendada. Sin una solo ves filas de ejemplo. | Cuenta gratuita en <https://www.themoviedb.org>, luego *Configuración > API* y pide una clave de desarrollador. Sirve la «API Key» (v3) o el «Read Access Token» (v4, empieza con `eyJ`). |
| `ANILIST_TOKEN` | No. Marca como vistos en tu cuenta de AniList los episodios de anime terminados en Crunchyroll. | <https://anilist.co/settings/developer>. |
| `EVENTOS_URL`, `EVENTOS_TOKEN`, `EVENTOS_DISPOSITIVO` | No. Envía eventos del launcher (vistas, búsquedas, Mi lista) a **tu propio** servidor. Vacío = no se envía nada. | Solo si tienes tu propio endpoint HTTP. |

**Si compilaste Lyra tú** (ruta C), pon las claves en `config.local.properties` antes de compilar.

**Si instalaste el APK publicado** (ruta A o B), entrega las claves a la app después de instalar. Crea un archivo de texto llamado `config.local.properties`, por ejemplo:

```
TMDB_READ_TOKEN=eyJ...tu-token...
```

y cópialo a `/sdcard/Android/data/co.davidvega.launcher/files/` en la tele. Con adb: `adb push config.local.properties /sdcard/Android/data/co.davidvega.launcher/files/`. O con una app de archivos en la tele que pueda escribir ahí (en Android 11 o superior los gestores de archivos no pueden escribir en esa carpeta; usa adb). Lyra prefiere ese archivo sobre lo incluido. Cierra Lyra del todo y ábrela de nuevo.

Tu perfil de gustos (región, idioma, tus apps de streaming, títulos que amas) está en `datos/gustos.json`. El README, sección *Your taste profile*, explica cómo cambiarlo, también sin recompilar.

## 6. Actualizar

- **Ruta A:** corre Downloader otra vez con la misma dirección. Instalar encima conserva tus ajustes. Si Android se niega por la firma, desinstala primero.
- **Ruta B/C:** `adb install -r <nuevo-apk>`. En Fire TV, `adb install -r` apaga el servicio de accesibilidad: actívalo de nuevo (paso 4) o usa `instalar.ps1`.

## 7. Desinstalar y volver al inicio original

```sh
adb shell settings put secure enabled_accessibility_services ""
adb uninstall co.davidvega.launcher
```

La primera línea apaga todos los servicios de accesibilidad (vacía la lista completa, así que vuelve a activar los demás que uses). Sin PC: *Configuración > Aplicaciones > Administrar aplicaciones instaladas > Lyra > Desinstalar*. El inicio de Amazon vuelve al instante; Lyra no cambia nada más en la tele. Si pusiste Lyra como inicio en Android TV, elige otra app de inicio primero, o simplemente desinstálala.

## 8. Problemas frecuentes

- **El foco se pierde o una pantalla no se puede recorrer.** Pulsa Atrás y entra de nuevo. Si sigue pasando, abre un issue con la pantalla y el aparato. Lyra se maneja solo con las flechas.
- **Pantalla negra o la intro parece trabada.** Espera unos 10 segundos, pulsa Atrás y abre Lyra otra vez. En *Ajustes > Intro* elige «ninguna» para saltar el vídeo. Cuenta el modelo de tu aparato si persiste.
- **Solo filas de ejemplo, sin recomendaciones reales.** Falta la clave de TMDB o está mal. Ponla ([paso 5](#5-poner-tus-claves)) y mira *Ajustes > Estado del sistema*.
- **No se reproducen los tráilers.** Se piden a YouTube con NewPipeExtractor y necesitan internet. YouTube cambia a menudo, así que los tráilers pueden dejar de funcionar hasta que se actualice la librería. Revisa primero tu conexión.
- **Aparece el inicio de Amazon y Lyra no se abre.** El servicio de accesibilidad está apagado: actívalo de nuevo (paso 4). También se apaga tras «Forzar detención» o tras `adb install -r`.
- **«App no instalada».** Hay otra compilación de Lyra con distinta firma (desinstálala primero) o el aparato es anterior a Android 9.
- **El orden por uso, lo que se reproduce o el mantenimiento no hacen nada.** Necesitan los permisos de adb del paso 4. *Ajustes > Estado del sistema* dice cuáles faltan.
- **adb no conecta.** Revisa que la depuración ADB esté activa, que tele y PC estén en la misma red y que aceptaste el aviso en la tele.

¿Encontraste un fallo? Abre un issue en <https://github.com/Danvegamo/lyra-tv-launcher/issues>, sin capturas de tu historial, direcciones IP ni nombres de cuenta.

*Lyra no está afiliada a Amazon, Google ni a ningún servicio de streaming. Fire TV, Android TV y otros nombres son de sus dueños.*
