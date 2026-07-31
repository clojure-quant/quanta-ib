# Interactive Brokers TWS for NixOS

## Run

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
