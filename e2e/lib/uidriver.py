"""Text-driven Android UI driver for E2E (works without vision).

Speaks only through adb + uiautomator dumps: the XML hierarchy is the "eyes",
and taps/scrolls/typing are the "hands". Every call is timeout-bounded.
"""

from __future__ import annotations

import html, re, subprocess, time
from dataclasses import dataclass

ADB = "adb"
APP = "com.personal.balance"


@dataclass
class Node:
    text: str
    desc: str
    package: str
    cls: str
    clickable: bool
    focused: bool
    enabled: bool
    bounds: tuple[tuple[int, int], tuple[int, int]]

    @property
    def center(self) -> tuple[int, int]:
        (x0, y0), (x1, y1) = self.bounds
        return (x0 + x1) // 2, (y0 + y1) // 2


def sh(*args: str, timeout: float = 60.0) -> str:
    r = subprocess.run([ADB, *args], capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=timeout)
    out = r.stdout or ""
    err = r.stderr or ""
    return (out + err).strip()


def dump(timeout: float = 90.0) -> str:
    """Return raw UI XML (best-effort, retries on flaky uiautomator)."""
    xml = ""
    for attempt in range(5):
        sh("shell", "uiautomator", "dump", "/sdcard/_ui.xml", timeout=timeout)
        xml = sh("shell", "cat", "/sdcard/_ui.xml", timeout=timeout)
        if xml.startswith("<?xml"):
            return xml
        time.sleep(2)
    raise RuntimeError("uiautomator dump failed 5x")


def parse(xml: str) -> list[Node]:
    nodes: list[Node] = []
    for m in re.finditer(r"<node[^>]*>", xml):
        tag = m.group(0)
        text = html.unescape(re.search(r'text="([^"]*)"', tag).group(1))
        desc = html.unescape(re.search(r'content-desc="([^"]*)"', tag).group(1))
        pkg = re.search(r'package="([^"]*)"', tag).group(1)
        cls = re.search(r'class="([^"]*)"', tag).group(1)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if bm is None:
            continue
        (x0, y0, x1, y1) = map(int, bm.groups())
        nodes.append(Node(text, desc, pkg, cls,
                      re.search(r'clickable="([^"]*)"', tag).group(1) == "true",
                      re.search(r'focused="([^"]*)"', tag).group(1) == "true",
                      re.search(r'enabled="([^"]*)"', tag).group(1) == "true",
                      ((x0, y0), (x1, y1))))
    return nodes


def texts(include_desc: bool = False) -> list[str]:
    ts = [n.text for n in parse(dump())]
    if include_desc:
        ts += [n.desc for n in parse(dump())]
    return [t for t in ts if t]


def foreground_package() -> str | None:
    """Best guess at the top-most window's package from the hierarchy nodes."""
    try:
        pkgs = [n.package for n in parse(dump())]
    except RuntimeError:
        return None
    for p in pkgs[:: max(1, len(pkgs) // 20)][:40]:
        if p:
            return p
    return pkgs[0] if pkgs else None


def state() -> tuple[str, list[Node]]:
    """Return (foreground package, all nodes) for screen-state logic."""
    ns = parse(dump())
    pkgs = [n.package for n in ns if n.package]
    top = pkgs[0] if pkgs else ""
    return top, ns


def fields(tag: str = "") -> list[Node]:
    """editable text fields present in the hierarchy."""
    try:
        return [n for n in parse(dump()) if "EditText" in n.cls]
    except RuntimeError as e:
        print(f"dump error: {e}")
        return []


def _area(bounds) -> int:
    (x0, y0), (x1, y1) = bounds
    return (x1 - x0) * (y1 - y0)


def _contains(outer, inner) -> bool:
    return (outer[0][0] <= inner[0][0] and outer[0][1] <= inner[0][1]
            and outer[1][0] >= inner[1][0] and outer[1][1] >= inner[1][1])


def tap_target(needle: str, include_desc: bool = False, occurrence: int = 0) -> Node | None:
    """Find the node to tap for a text label: if the matched text sits inside a
    clickable container (Compose puts button text on a child node), return the
    smallest enclosing clickable node; else return the text node itself."""
    pat = re.compile(needle)
    ns = parse(dump())
    hits = [n for n in ns if pat.search(n.text)]
    if include_desc:
        hits += [n for n in ns if pat.search(n.desc)]
    if not hits:
        return None
    results: list[Node] = []
    for cand in hits:
        best = cand if cand.clickable else None
        for o in ns:
            if o.clickable and _contains(o.bounds, cand.bounds):
                if best is None or _area(o.bounds) < _area(best.bounds):
                    best = o
        results.append(best or cand)
    clickable_res = [r for r in results if r.clickable]
    pool = clickable_res or results
    return pool[occurrence] if len(pool) > occurrence else pool[-1]


def find(needle: str, include_desc: bool = False, occurrence: int = 0,
         prefer_clickable: bool = True) -> Node | None:
    pat = re.compile(needle)
    ns = parse(dump())
    hits = [n for n in ns if pat.search(n.text)]
    if include_desc:
        hits += [n for n in ns if pat.search(n.desc)]
    if not hits:
        return None
    if prefer_clickable:
        clickable = [n for n in hits if n.clickable]
        pool = clickable or hits
    else:
        pool = hits
    return pool[occurrence] if len(pool) > occurrence else None


def tap_text(needle: str, include_desc: bool = False, occurrence: int = 0) -> Node:
    n = tap_target(needle, include_desc, occurrence)
    if n is None:
        raise RuntimeError(f"tap_text: '{needle}' not found")
    sh("shell", "input", "tap", str(n.center[0]), str(n.center[1]))
    return n


def tap_xy(x: int, y: int) -> None:
    sh("shell", "input", "tap", str(x), str(y))


def swipe(x0: int, y0: int, x1: int, y1: int, ms: int = 300) -> None:
    sh("shell", "input", "swipe", str(x0), str(y0), str(x1), str(y1), str(ms))


def type_text(text: str) -> None:
    """Text via the IME 'text' command (fast; can be flaky for masked fields)."""
    sh("shell", "input", "text", text)


def type_codes(s: str) -> None:
    """Type letter/digit/punct runs via explicit KeyEvents - deterministic and
    immune to IME quirks, so the same input is byte-identical every time."""
    codes: list[str] = []
    for ch in s:
        if "a" <= ch <= "z":
            codes.append(str(ord(ch) - ord("a") + 29))
        elif "A" <= ch <= "Z":
            codes.append(str(ord(ch) - ord("A") + 29))  # unshifted; avoid in practice
        elif "0" <= ch <= "9":
            codes.append(str(ord(ch) - ord("0") + 7))
        elif ch == "-":
            codes.append("69")   # KEYCODE_MINUS
        elif ch == ".":
            codes.append("56")   # KEYCODE_PERIOD
        elif ch == "@":
            codes.append("77")   # KEYCODE_AT
        elif ch == "_":
            codes.append("71")   # KEYCODE_UNDERSCORE
        else:
            raise ValueError(f"type_codes: unsupported char {ch!r}")
    for i in range(0, len(codes), 40):
        sh("shell", "input", "keyevent", *codes[i:i+40])


def keyevent(key: str | int) -> None:
    sh("shell", "input", "keyevent", str(key))


def launch_app() -> None:
    sh("shell", "monkey", "-p", APP, "-c", "android.intent.category.LAUNCHER", "1")


def force_stop() -> None:
    sh("shell", "am", "force-stop", APP)


def force_stop_harness() -> None:
    sh("shell", "am", "force-stop", "dev.mobile.maestro.test")


def ime_shown() -> bool:
    out = sh("shell", "dumpsys", "input_method")
    return "mInputShown=true" in out


def hide_keyboard() -> None:
    """Press BACK only when the IME is actually up, so BACK never navigates the
    app (which would exit to the previous task)."""
    for _ in range(3):
        if not ime_shown():
            return
        keyevent(4)
        time.sleep(0.8)


def reveal_and_tap_text(needle: str, below: int = 860, include_desc: bool = False) -> Node:
    """Tap a label (or its enclosing clickable). If the IME is up and would cover
    the target, hide it first; if the target is off-screen, scroll to reach it.
    Visible on-screen targets are tapped directly - no gratuitous scrolling."""
    for _ in range(8):
        n = tap_target(needle, include_desc)
        if n is None:
            if ime_shown():
                hide_keyboard()
                time.sleep(0.6)
                continue
            swipe(360, 1100, 360, 700)
            time.sleep(0.5)
            continue
        x, y = n.center
        if ime_shown() and y > below:
            hide_keyboard()
            time.sleep(0.6)
            continue
        if y > 1480:  # effectively off-screen on a 1600px display
            swipe(360, 1300, 360, 800)
            time.sleep(0.5)
            continue
        tap_xy(x, y)
        return n
    raise RuntimeError(f"reveal_and_tap_text: '{needle}' unreachable")


def wait_text(needle: str, timeout: float, include_desc: bool = False) -> Node | None:
    pat = re.compile(needle)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            for n in parse(dump()):
                if pat.search(n.text) or (include_desc and pat.search(n.desc)):
                    return n
        except RuntimeError:
            pass
        time.sleep(2)
    return None


def screenshot_local(name: str) -> None:
    sh("exec-out", "screencap", "-p", ">", name)