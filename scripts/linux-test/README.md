# GameNative Linux test suite

Exercises the Linux features a user sees, per distro, on a real device over adb, and writes a
report. It drives the app the way a person would: intents to open screens, taps on controls found
through `uiautomator` ids (the `automationIds()` test tags), and commands typed into the Linux
Terminal. GameNative has no remote-control API for it.

```bash
scripts/linux-test/run-linux-tests.sh -s 192.168.1.208:5555 --yes
scripts/linux-test/run-linux-tests.sh -s <googlebook-ip:port> --user 10 --distros "debian" --yes
```

| Option | Meaning |
|---|---|
| `--user` | Android user GameNative runs in (0 on Bass; 10 on the Googlebook) |
| `--distros` | Distro ids, default `ubuntu debian archlinux` |
| `--tests` | `A` install and remove, `B` shell basics, `C` terminal (default `A B C`) |
| `--keep` | Leave the distros installed afterwards |
| `--no-install` | Only test distros that are already installed |
| `--out` | Report directory on the host (default `./linux-test-reports`) |

The run is started detached on the device and survives a dropped adb connection; the host polls
`/data/local/tmp/gn-linux-test/status` and pulls the run directory when it reads `DONE`.

## Pieces

- `run-linux-tests.sh` (host): pushes the scripts, grants all-files access (PRoot binds shared
  storage only with it), starts the run, follows it and pulls the report.
- `linux-test.sh` (device, `/system/bin/sh`): the steps. Writes `results.tsv`, `report.md`,
  `logs/<distro>-<test>.logcat` (this app's logcat for the step), `logs/<distro>-probe-<test>.log`
  and `shots/<distro>-<test>.png`.
- `probe.sh` (guest, POSIX `sh`): typed into the Linux Terminal as
  `sh /mnt/storage/internal/Download/gn-linux-test/probe.sh <tests>`; writes `<test>.result`,
  `<test>.log` and `<test>.done` under `Download/gn-linux-test/results/<distro>/`.

## Tests

- **A** install through the Linux Distros screen (waits for `[LinuxDistros]: installed <id>` or
  `[LinuxRootfs]: installed to`), and remove through the same screen at the end unless `--keep`.
- **B** shell basics inside the distro: `info` (os-release, rootfs size), `net` (DNS, HTTPS when
  curl or wget exists), `pkg` (refresh, install and remove `nano`), `user` (create a user, `su`).
- **C** the terminal opens on the distro (`linux_distro` intent extra) and answers the probe.

## Notes

- It installs and removes distros and types into the terminal: only point it at a test device, or
  tell whoever is using the device first. It keeps the screen on while it runs.
- Never reset with `pm clear` or by uninstalling GameNative: that deletes games and containers.
  The suite only removes distros through the app's own Remove button.
