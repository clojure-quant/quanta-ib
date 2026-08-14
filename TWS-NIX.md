# Interactive Brokers TWS for NixOS

A flake packaging the current Interactive Brokers Trader Workstation **Offline
Stable** release for x86_64 NixOS.

This is a modernized flake conversion of
[bitonic's original Nix expression](https://gist.github.com/bitonic/a14cdcce5c9c731f793611cf3e3c113d).
It preserves the original approach: Nix fetches a pinned IBKR installer, unpacks
it during the build, and runs TWS in an FHS environment. The installer is not
downloaded or executed when TWS starts, and Steam is not used.

## Version

- TWS Offline Stable: `10.45.1j`
- Platform: `x86_64-linux`
- Installer SHA-256: `sha256-+hm3dgJ+q23fmddTqsV/4b62sG4xHqpU/6g0M/7p6YY=`

## Run

```console
nix run github:clojure-quant/ib
```

For a local checkout:

```console
nix run .
```

The first time this command runs, Nix builds the package if it is not already
available in the Nix store. During that build, Nix downloads the pinned IBKR
Offline Stable installer and unpacks TWS non-interactively into the immutable
Nix store. It does not install TWS into `~/.local/share` or another conventional
system directory.

After the build completes, `nix run .` launches TWS. Subsequent runs reuse the
existing package from the Nix store and only launch the application unless the
flake, its inputs, or the pinned TWS package changes. Writable TWS configuration
is stored separately in `~/.tws`.

## Install

```console
nix profile install github:clojure-quant/ib
ib-tws
```

The package also provides `tws` as an alias and installs a desktop entry.

TWS stores its writable configuration in `~/.tws`. Override that location with
`TWS_CONFIG_DIR`.

## NixOS configuration

Add the package directly from the flake:

```nix
{
  inputs.ib.url = "github:clojure-quant/ib";

  outputs = { nixpkgs, ib, ... }: {
    nixosConfigurations.your-host = nixpkgs.lib.nixosSystem {
      system = "x86_64-linux";
      modules = [
        ({ pkgs, ... }: {
          nixpkgs.config.allowUnfree = true;
          environment.systemPackages = [ ib.packages.${pkgs.system}.ib-tws ];
        })
      ];
    };
  };
}
```

Interactive Brokers publishes Offline Stable as a floating URL (no versioned
download). This repo pins `version` + `hash` in `package.nix`. A daily GitHub
Action checks
`https://download2.interactivebrokers.com/installers/tws/stable-standalone/version.json`
and opens a PR when `buildVersion` changes. Locally:

```console
./scripts/update-tws-pin.sh
```

The prefetch must use `nix-prefetch-url --executable` so the NAR hash matches
`fetchurl { executable = true; }`. After merging a pin bump, flake consumers
run `nix flake update ib`.
