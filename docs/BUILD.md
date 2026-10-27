# Сборка из отдельного checkout

## Android APK

Проверяемая конфигурация: Linux x86_64, JDK 17, Python 3, Bash, OpenSSL, zip,
Android SDK Build Tools 34.0.0 и SDK Platform 30. Java-компиляция использует Java 8,
`minSdk=26`, `targetSdk=29`. Gradle, Maven и соседние репозитории не используются.

На Debian/Ubuntu системные зависимости можно поставить так:

```bash
sudo apt-get install openjdk-17-jdk python3 adb openssl zip
```

Установите Android Command-line Tools из [официального SDK](https://developer.android.com/studio#command-tools),
затем компоненты (лицензии SDK принимаются пользователем через `sdkmanager`):

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
  'platform-tools' 'build-tools;34.0.0' 'platforms;android-30'
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
./scripts/init-keys.sh
./build.sh
./tests/run-host-tests.sh
./tests/build.sh
```

`JAVA_HOME` при необходимости указывает на JDK. Для нестандартного расположения SDK:

```bash
export ANDROID_BUILD_TOOLS=/path/to/build-tools
export ANDROID_PLATFORM=/path/to/android.jar
```

`init-keys.sh` создаёт новый RSA-ключ ADB и собственный ключ подписи с случайным
паролем. Повторный вызов сохраняет существующие ключи. Файлы имеют режим 0600,
каталог — 0700. Скрипт не читает ключи из `~/.android` и не использует ключ автора.
В Git эти файлы не попадают. **Сохраните свою пару ключей и пароль вне репозитория**:
новая подпись не позволит обновить вашу уже установленную сборку.

Вместо генерации можно задать свои пути:

| Переменная | Назначение |
| --- | --- |
| `SIGNING_KEYSTORE` | JKS/PKCS12 хранилище с единственным ключом |
| `SIGNING_STORE_PASS_FILE` | Файл с паролем хранилища в первой строке |
| `SIGNING_KEY_PASS_FILE` | Отдельный файл с паролем ключа, если отличается |
| `APP_ADB_KEY` | RSA private key в PEM PKCS#8 без шифрования |
| `APP_ADB_PUB` | Соответствующий public key в формате Android ADB |

Сборка проверяет `app/assets/qnx/manifest.properties`, компилирует ресурсы через
`aapt2`, Java через `javac`, DEX через `d8`, затем выполняет `zipalign` и `apksigner`.
Ключи ADB добавляются только во временные assets в `build/android/`, исходный
каталог `app/assets/` остаётся без ключей. APK содержит ваш ADB private key:
он требуется существующему механизму локального доступа. Учитывайте это при
передаче собственных сборок. Пароль и ключ подписи в APK не включаются.

## QNX-демон из C++

Для обычной сборки APK этот шаг не нужен: проверенный бинарник уже включён.
Для изменения алгоритма потребуются Docker и три внешние зависимости:

1. Qualcomm SNPE **1.55.0.2958**, включая `include/zdl/`.
2. QCARCAM **4.7**: `qcarcam.h`, `qcarcam_types.h` из соответствующего SDK.
3. Библиотеки той же QNX-прошивки F515: `libSNPE.so`, `libais_client.so`,
   `libpmem_client.so`, `libsocket.so.3`, `libc.so.4`, `libm.so.3`, `libc++.so.1`.

Получите SDK отдельно по условиям Qualcomm, а библиотеки — из собственной копии
прошивки/своего ГУ. Импорт принимает локальное дерево файлов, поэтому конкретные
каталоги выгрузки не важны. Нужные имена и эталонные хэши перечислены в
[`native/dependencies.json`](../native/dependencies.json). Скрипт отвергает
библиотеки и заголовки другой версии: совпадение имён не означает совместимость ABI.

```bash
python3 native/import-deps.py \
  --snpe /path/to/snpe-1.55.0.2958 \
  --qcarcam /path/to/qcarcam-4.7/include \
  --runtime /path/to/extracted-qnx-libraries
./native/build.sh
```

Зависимости копируются в исключённый из Git `native/deps/`. Сборка Docker-образа
`f515-qnx-build` выполняется автоматически при первом запуске. В контейнер
передаётся только этот checkout; доступа к соседним рабочим папкам нет.
Образ использует Debian bookworm, clang/lld/libc++ 14 и заголовки arm64.
Результат — `native/build/fcwd`, ELF64 AArch64, динамический загрузчик QNX
`/usr/lib/ldqnx-64.so.2`.

Это проектный cross-toolchain, а не универсальная замена QNX SDP. Используются
собственный `_start`, известные QNX-константы, адаптация `errno`, scanf и ABI.
Раскрутка C++ исключений не реализована: `_Unwind_Resume` завершает процесс.
Код SNPE компилируется с `-fexceptions`, но рабочий путь не должен бросать исключения.

Из реальных библиотек создаются заглушки только для линковки: часть файлов QNX
не имеет section headers, и обычный линковщик не видит экспортов. После сборки
каждый импорт бинарника проверяется по экспортам **реальных** библиотек.
`native/build/stubs/` никогда не копируется на ГУ.

Для включения изменённого демона в APK выполните явно:

```bash
./scripts/update-payload.sh native
git diff -- app/assets/qnx/manifest.properties
./build.sh
```

Обычная сборка не подхватывает локальный `native/build/fcwd` автоматически.
Новый бинарник сначала нужно проверить на целевой прошивке; успешная линковка
сама по себе не проверяет ABI структур и поведение камеры/DSP.

## Модель и вспомогательные инструменты

Пересборка модели описана в [models/README.md](../models/README.md). Готовый DLC
включён в APK; для него не нужны Python-пакеты машинного обучения на компьютере.

Диагностические программы `native/android-tools/*.c` можно отдельно собрать
Android NDK для arm64 API 26:

```bash
ANDROID_NDK_HOME=/path/to/android-ndk ./native/android-tools/build.sh
```

Эти программы не нужны приложению для обычной работы. Тестовые C++ программы
в `native/src/` сохранены как диагностика; основной build собирает только `fcwd`.

## Собственный канал обновлений

Сборка по умолчанию сохраняет официальный URL проверки, но официальный APK имеет
другой сертификат и не установится поверх вашей сборки. Для своего канала измените
`UpdateManager.LATEST_URL`, разрешённые host/path в `ReleaseMetadata.validateUrl`
и соответствующие проверки `tests/PayloadReleaseTest.java`. Не отключайте
проверку подписи. Затем:

```bash
RELEASE_BASE_URL=https://updates.example.org/f515/adas-qnx ./scripts/make-release.sh
```

Скрипт создаёт APK и `build/latest.json` **локально**. Он не знает SSH-сервера и
ничего не публикует. На своём сервере сначала разместите неизменяемый версионный
APK, проверьте SHA-256, затем атомарно обновите JSON. Для изменившихся байтов нужны
новые versionName/versionCode. Никогда не заменяйте официальный 0.1.5 своей сборкой
под тем же URL.
