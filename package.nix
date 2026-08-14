{ pkgs }:
let
  installerEnv = pkgs.buildFHSEnv {
    name = "ib-tws-installer-env";
    targetPkgs =
      p: with p; [
        coreutils
        glib
        stdenv.cc.cc
      libx11
      libxext
      libxi
      libxrender
      libxtst
        zlib
      ];
    runScript = "bash";
  };

  ibTwsUnwrapped = pkgs.stdenvNoCC.mkDerivation {
    pname = "ib-tws-unwrapped";
    version = "10.45.1j";

    src = pkgs.fetchurl {
      url = "https://download2.interactivebrokers.com/installers/tws/stable-standalone/tws-stable-standalone-linux-x64.sh";
      hash = "sha256-GkTIMbhr4bEeX2oImsxkBXc2ZT9pdhL88pEfNPIm2vE=";
      executable = true;
    };

    preferLocalBuild = true;
    dontUnpack = true;

    installPhase = ''
      runHook preInstall

      export HOME="$TMPDIR/home"
      export INSTALL4J_KEEP_TEMP=yes
      export INSTALL4J_TEMP="$TMPDIR/install4j"
      mkdir -p "$HOME" "$INSTALL4J_TEMP" "$out/libexec" "$out/bin"

      ${installerEnv}/bin/ib-tws-installer-env "$src" -q -dir "$out/libexec"

      if [ ! -x "$out/libexec/jre/bin/java" ]; then
        java_path="$(find "$INSTALL4J_TEMP" -path '*/jre/bin/java' -type f -print -quit)"
        jre_dir="''${java_path%/bin/java}"
        if [ -z "$jre_dir" ]; then
          echo "The TWS installer did not unpack its bundled JRE" >&2
          exit 1
        fi
        cp -a "$jre_dir" "$out/libexec/jre"
      fi

      substituteInPlace "$out/libexec/tws" \
        --replace-fail 'read_vmoptions "$prg_dir/$progname.vmoptions"' 'read_vmoptions "$HOME/.tws/$progname.vmoptions"'

      substitute ${./scripts/tws-native.sh} "$out/bin/ib-tws-native" \
        --replace-fail '@libexec@' "$out/libexec"
      chmod +x "$out/bin/ib-tws-native"

      runHook postInstall
    '';

    meta = with pkgs.lib; {
      description = "Interactive Brokers Trader Workstation Offline Stable";
      homepage = "https://www.interactivebrokers.com/en/trading/tws.php";
      license = licenses.unfree;
      platforms = [ "x86_64-linux" ];
      sourceProvenance = [ sourceTypes.binaryNativeCode ];
    };
  };
in
pkgs.buildFHSEnv {
  name = "ib-tws";

  targetPkgs =
    p: with p; [
      ibTwsUnwrapped
      alsa-lib
      atk
      at-spi2-atk
      at-spi2-core
      cairo
      cups
      dbus
      expat
      fontconfig
      freetype
      glib
      gtk3
      libdrm
      libGL
      libxkbcommon
      mesa
      nspr
      nss
      pango
      stdenv.cc.cc
    libx11
    libxcomposite
    libxcursor
    libxdamage
    libxext
    libxfixes
    libxi
    libxrandr
    libxrender
    libxtst
    libxcb
    libxshmfence
      zlib
    ];

  runScript = "${ibTwsUnwrapped}/bin/ib-tws-native";

  extraInstallCommands = ''
    ln -s ib-tws "$out/bin/tws"
    install -Dm644 ${./ib-tws.desktop} "$out/share/applications/ib-tws.desktop"
    install -Dm644 ${ibTwsUnwrapped}/libexec/.install4j/tws.png \
      "$out/share/icons/hicolor/128x128/apps/ib-tws.png"
  '';

  meta = ibTwsUnwrapped.meta;
}
