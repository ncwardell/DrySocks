{
  description = "SocksChain — send a phone's traffic through a chain of SOCKS and HTTP proxies";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };

        # The Android SDK is unfree and its licence must be accepted non-interactively,
        # so the Android shell needs its own nixpkgs instance. The default shell does not.
        androidPkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # Matches app/build.gradle.kts: compileSdk 37.1 (tor-android needs it), AGP 9.4.
        # NDK r28 is the first to align native libraries to 16 KB by default.
        ndkVersion = "28.2.13676358";
        androidSdk = androidPkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "37.1" ];
          buildToolsVersions = [ "37.0.0" ];
          includeNDK = true;
          ndkVersions = [ ndkVersion ];
          includeEmulator = false;
          includeSystemImages = false;
          includeSources = false;
        };

        # tun2socks v2.7 needs Go 1.26. GOTOOLCHAIN=local stops Go fetching its own.
        go = pkgs.go_1_26;

        commonTools = [
          go
          pkgs.gcc # cgo, for `go test -race`
          pkgs.jdk17
          pkgs.gradle_9 # AGP 9 needs Gradle 9
        ];

        commonHook = ''
          export JAVA_HOME="${pkgs.jdk17}"
          export GOTOOLCHAIN=local
          # Keep Gradle's and Go's caches inside the project so they never escape into $HOME,
          # and at its root, so entering the shell from engine/ does not start a second one.
          root="$(git rev-parse --show-toplevel 2>/dev/null || echo "$PWD")"
          export GRADLE_USER_HOME="''${GRADLE_USER_HOME:-$root/.gradle-home}"
          export GOPATH="''${GOPATH:-$root/.go}"
          export GOFLAGS=-modcacherw
        '';
      in
      {
        devShells = {
          # `nix develop` — enough for the engine's tests and the :core tests.
          # Deliberately excludes the Android SDK so the shell is cheap to enter.
          default = pkgs.mkShell {
            name = "sockschain";
            packages = commonTools;

            shellHook = commonHook + ''
              echo "SocksChain dev shell"
              echo "  (cd engine && go test -race ./...)   the chain, DNS and engine"
              echo "  gradle :core:test                     profiles and routes"
              echo
              echo "  nix develop .#android                 adds the SDK and NDK for the APK"
            '';
          };

          # `nix develop .#android` — the above plus the SDK and NDK.
          android = androidPkgs.mkShell {
            name = "sockschain-android";
            packages = commonTools ++ [
              androidSdk.androidsdk
              androidPkgs.android-tools
            ];

            shellHook = commonHook + ''
              export ANDROID_HOME="${androidSdk.androidsdk}/libexec/android-sdk"
              export ANDROID_SDK_ROOT="$ANDROID_HOME"
              export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/${ndkVersion}"

              # AGP shells out to aapt2; the Nix-built one must be used in place of the
              # copy AGP unpacks from its own artifact, which is not patchelf'd.
              export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/37.0.0/aapt2"

              echo "SocksChain Android shell — SDK at $ANDROID_HOME"
              echo "  engine/build-aar.sh          the Go engine, into app/libs/engine.aar"
              echo "  gradle :app:assembleDebug    the APK"
            '';
          };
        };
      });
}
