import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  HypothesisTracker,
  buildScriptIndex,
  matchHypothesis,
  normalizeWord as normalize,
} from "@/lib/voice-match";

type ScreenT = "input" | "prompter";

export default function App() {
  const [screen, setScreen] = useState<ScreenT>("input");
  const [script, setScript] = useState("");

  return screen === "input" ? (
    <InputScreen
      script={script}
      setScript={setScript}
      onStart={() => script.trim() && setScreen("prompter")}
    />
  ) : (
    <Prompter script={script} onBack={() => setScreen("input")} />
  );
}

type SavedScript = { id: string; title: string; text: string; updatedAt: number };
const SCRIPTS_KEY = "prompter_scripts";

function loadSaved(): SavedScript[] {
  try {
    const raw = localStorage.getItem(SCRIPTS_KEY);
    if (!raw) return [];
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}
function writeSaved(list: SavedScript[]) {
  try {
    localStorage.setItem(SCRIPTS_KEY, JSON.stringify(list));
  } catch {}
}
function deriveTitle(text: string): string {
  const line = text.split(/\r?\n/).map((l) => l.trim()).find((l) => l.length > 0) || "제목 없음";
  return line.slice(0, 30);
}
function upsertScript(list: SavedScript[], title: string, text: string): SavedScript[] {
  const now = Date.now();
  const idx = list.findIndex((s) => s.title === title);
  if (idx >= 0) {
    const copy = list.slice();
    copy[idx] = { ...copy[idx], text, updatedAt: now };
    return copy;
  }
  return [...list, { id: `${now}-${Math.random().toString(36).slice(2, 8)}`, title, text, updatedAt: now }];
}
function fmtDate(ts: number): string {
  const d = new Date(ts);
  return `${d.getFullYear()}.${d.getMonth() + 1}.${d.getDate()}`;
}

function InputScreen({
  script,
  setScript,
  onStart,
}: {
  script: string;
  setScript: (s: string) => void;
  onStart: () => void;
}) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [saved, setSaved] = useState<SavedScript[]>(() => loadSaved());
  const [pendingTitle, setPendingTitle] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const showToast = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 1400);
  };

  const onFile = async (f: File) => {
    const nameNoExt = f.name.replace(/\.[^.]+$/, "");
    try {
      const lower = f.name.toLowerCase();
      if (lower.endsWith(".docx")) {
        const arrayBuffer = await f.arrayBuffer();
        const mammoth = await import("mammoth/mammoth.browser");
        const result = await (mammoth as any).extractRawText({ arrayBuffer });
        setScript(result.value || "");
      } else {
        const text = await f.text();
        setScript(text);
      }
      setPendingTitle(nameNoExt.slice(0, 30) || null);
    } catch (e) {
      console.error(e);
      alert("파일을 읽지 못했습니다");
    }
  };

  const doSave = () => {
    const text = script;
    if (!text.trim()) return;
    const title = pendingTitle && pendingTitle.trim() ? pendingTitle.trim().slice(0, 30) : deriveTitle(text);
    const next = upsertScript(saved, title, text);
    setSaved(next);
    writeSaved(next);
    showToast("저장됨");
  };

  const doStart = () => {
    if (!script.trim()) return;
    const title = pendingTitle && pendingTitle.trim() ? pendingTitle.trim().slice(0, 30) : deriveTitle(script);
    const next = upsertScript(saved, title, script);
    setSaved(next);
    writeSaved(next);
    onStart();
  };

  const loadScript = (s: SavedScript) => {
    setScript(s.text);
    setPendingTitle(s.title);
  };

  const removeScript = (s: SavedScript) => {
    if (!confirm(`"${s.title}" 대본을 삭제하시겠습니까?`)) return;
    const next = saved.filter((x) => x.id !== s.id);
    setSaved(next);
    writeSaved(next);
  };

  const sorted = [...saved].sort((a, b) => b.updatedAt - a.updatedAt);

  return (
    <div
      style={{
        minHeight: "100vh",
        background: "#000",
        color: "#e5e5e5",
        padding: "24px 18px",
        display: "flex",
        flexDirection: "column",
        gap: 16,
        fontFamily:
          '-apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Malgun Gothic", system-ui, sans-serif',
      }}
    >
      <h1 style={{ fontSize: 26, fontWeight: 800, color: "#ffd23f", margin: 0 }}>
        🎙 음성 프롬프터
      </h1>
      <p style={{ margin: 0, color: "#9a9a9a", fontSize: 14 }}>대본 입력</p>

      <textarea
        value={script}
        onChange={(e) => setScript(e.target.value)}
        placeholder="대본을 여기에 붙여넣으세요"
        style={{
          minHeight: "40vh",
          background: "#0d0d0d",
          color: "#e5e5e5",
          border: "1px solid #2a2a2a",
          borderRadius: 12,
          padding: 16,
          fontSize: 17,
          lineHeight: 1.6,
          resize: "vertical",
          fontFamily: "inherit",
          wordBreak: "keep-all",
        }}
      />

      <input
        ref={fileRef}
        type="file"
        accept=".txt,.docx,text/plain,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        style={{ display: "none" }}
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) void onFile(f);
          e.target.value = "";
        }}
      />

      <div style={{ display: "flex", gap: 10 }}>
        <button
          onClick={() => fileRef.current?.click()}
          style={{
            flex: 1,
            padding: "14px 18px",
            background: "#1a1a1a",
            color: "#e5e5e5",
            border: "1px solid #2a2a2a",
            borderRadius: 12,
            fontSize: 15,
            fontWeight: 600,
            fontFamily: "inherit",
          }}
        >
          📄 파일 불러오기 (txt/docx)
        </button>
        <button
          onClick={doSave}
          disabled={!script.trim()}
          style={{
            padding: "14px 18px",
            background: "#1a1a1a",
            color: script.trim() ? "#ffd23f" : "#666",
            border: "1px solid #2a2a2a",
            borderRadius: 12,
            fontSize: 15,
            fontWeight: 700,
            fontFamily: "inherit",
          }}
        >
          💾 저장
        </button>
        <button
          onClick={() => {
            const cur = script;
            const isSaved = saved.some((s) => s.text === cur);
            if (!isSaved) {
              if (!window.confirm("입력된 대본을 지우시겠습니까?")) return;
            }
            setScript("");
            setPendingTitle(null);
          }}
          disabled={!script.trim()}
          style={{
            padding: "14px 18px",
            background: "#1a1a1a",
            color: script.trim() ? "#bbb" : "#555",
            border: "1px solid #2a2a2a",
            borderRadius: 12,
            fontSize: 15,
            fontWeight: 700,
            fontFamily: "inherit",
          }}
        >
          🗑 지우기
        </button>
      </div>

      <button
        onClick={doStart}
        disabled={!script.trim()}
        style={{
          padding: "18px",
          background: script.trim() ? "#ffd23f" : "#3a3a3a",
          color: script.trim() ? "#000" : "#777",
          border: "none",
          borderRadius: 14,
          fontSize: 19,
          fontWeight: 800,
          fontFamily: "inherit",
        }}
      >
        프롬프터 시작 ▶
      </button>

      <div style={{ display: "flex", flexDirection: "column", gap: 8, marginTop: 4 }}>
        <p style={{ margin: 0, color: "#9a9a9a", fontSize: 14, fontWeight: 600 }}>내 대본</p>
        {sorted.length === 0 ? (
          <p style={{ margin: 0, color: "#5a5a5a", fontSize: 13 }}>저장된 대본이 없습니다</p>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            {sorted.map((s) => (
              <div
                key={s.id}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 8,
                  background: "#0d0d0d",
                  border: "1px solid #2a2a2a",
                  borderRadius: 10,
                  padding: "10px 12px",
                }}
              >
                <button
                  onClick={() => loadScript(s)}
                  style={{
                    flex: 1,
                    background: "transparent",
                    border: "none",
                    color: "#e5e5e5",
                    textAlign: "left",
                    fontFamily: "inherit",
                    padding: 0,
                    display: "flex",
                    flexDirection: "column",
                    gap: 2,
                  }}
                >
                  <span style={{ fontSize: 15, fontWeight: 600 }}>{s.title}</span>
                  <span style={{ fontSize: 12, color: "#6a6a6a" }}>{fmtDate(s.updatedAt)}</span>
                </button>
                <button
                  onClick={() => removeScript(s)}
                  style={{
                    background: "transparent",
                    color: "#8a8a8a",
                    border: "1px solid #2a2a2a",
                    borderRadius: 8,
                    padding: "6px 10px",
                    fontSize: 12,
                    fontFamily: "inherit",
                  }}
                >
                  삭제
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      <p style={{ margin: 0, color: "#666", fontSize: 12, textAlign: "center" }}>
        Chrome (Android) 또는 Safari (iPhone)에서 최적화되어 있습니다.
      </p>

      {toast && (
        <div
          style={{
            position: "fixed",
            bottom: 32,
            left: "50%",
            transform: "translateX(-50%)",
            background: "#ffd23f",
            color: "#000",
            padding: "10px 18px",
            borderRadius: 999,
            fontSize: 14,
            fontWeight: 700,
            boxShadow: "0 6px 20px rgba(0,0,0,0.5)",
          }}
        >
          {toast}
        </div>
      )}
    </div>
  );
}

type Lang = "ko-KR" | "ko-mixed" | "en-US";

function Prompter({ script, onBack }: { script: string; onBack: () => void }) {
  // Split into blocks (paragraphs) by newline. Each block has tokens.
  const blocks = useMemo(() => {
    const lines = script.split(/\r?\n/);
    return lines.map((line) => {
      if (!line.trim()) return { tokens: [] as { text: string; isWord: boolean; norm: string }[], isEmpty: true };
      const parts: { text: string; isWord: boolean; norm: string }[] = [];
      const segs = line.split(/(\s+)/);
      for (const s of segs) {
        if (!s) continue;
        if (/^\s+$/.test(s)) parts.push({ text: s, isWord: false, norm: "" });
        else parts.push({ text: s, isWord: true, norm: normalize(s) });
      }
      return { tokens: parts, isEmpty: false };
    });
  }, [script]);

  const wordNorms = useMemo(() => {
    const arr: string[] = [];
    for (const b of blocks) for (const t of b.tokens) if (t.isWord) arr.push(t.norm);
    return arr;
  }, [blocks]);

  const scriptIndex = useMemo(() => buildScriptIndex(wordNorms), [wordNorms]);
  const scriptIndexRef = useRef(scriptIndex);
  useEffect(() => {
    scriptIndexRef.current = scriptIndex;
  }, [scriptIndex]);

  const [wordIndex, setWordIndex] = useState(0); // index in wordNorms
  const wordIndexRef = useRef(0);
  useEffect(() => {
    wordIndexRef.current = wordIndex;
  }, [wordIndex]);

  const [playing, setPlaying] = useState(false);
  const playingRef = useRef(false);
  useEffect(() => {
    playingRef.current = playing;
  }, [playing]);

  const [voiceOn, setVoiceOn] = useState(true);
  const voiceOnRef = useRef(true);
  useEffect(() => {
    voiceOnRef.current = voiceOn;
  }, [voiceOn]);

  const [mirror, setMirror] = useState(false);
  const [lang, setLang] = useState<Lang>("ko-KR");
  const langRef = useRef<Lang>("ko-KR");
  useEffect(() => {
    langRef.current = lang;
  }, [lang]);

  const [fontSize, setFontSize] = useState(46);
  const [manualSpeed, setManualSpeed] = useState(60);
  const manualSpeedRef = useRef(60);
  useEffect(() => {
    manualSpeedRef.current = manualSpeed;
  }, [manualSpeed]);

  const [linePos, setLinePos] = useState(32);
  const linePosRef = useRef(32);
  useEffect(() => {
    linePosRef.current = linePos;
  }, [linePos]);


  const [showControls, setShowControls] = useState(true);
  const [countdown, setCountdown] = useState<number | null>(null);
  const [listening, setListening] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);
  const wordElsRef = useRef<Array<HTMLSpanElement | null>>([]);

  // wps EMA
  const wpsRef = useRef(2.2);
  const lastAdvanceTimeRef = useRef<number>(0);
  const lastVoiceMatchTimeRef = useRef<number>(performance.now());
  const maxTargetRef = useRef(0);
  const smoothedLeadRef = useRef(0);

  // px/s reading speed estimated from accepted recognition advances + DOM geometry
  const targetVelRef = useRef(0);

  const advanceIndex = useCallback((newIdx: number) => {
    const now = performance.now();
    const prev = wordIndexRef.current;
    if (newIdx > prev) {
      const dw = newIdx - prev;
      const dt = (now - (lastAdvanceTimeRef.current || now)) / 1000;
      if (lastAdvanceTimeRef.current && dt > 0.05) {
        let inst = dw / dt;
        if (inst > 6) inst = 6;
        wpsRef.current = wpsRef.current * 0.7 + inst * 0.3;
        // vertical speed from the real DOM distance actually covered
        const els = wordElsRef.current;
        const a = els[Math.min(prev, els.length - 1)];
        const b = els[Math.min(newIdx, els.length - 1)];
        if (a && b) {
          const dy = b.offsetTop - a.offsetTop;
          if (dy >= 0) {
            const v = Math.max(0, Math.min(1200, dy / dt));
            targetVelRef.current = targetVelRef.current * 0.6 + v * 0.4;
          }
        }
      }
      lastAdvanceTimeRef.current = now;
      lastVoiceMatchTimeRef.current = now;
      wordIndexRef.current = newIdx;
      setWordIndex(newIdx);
    }
  }, []);


  // ---- Speech recognition ----
  const recognitionRef = useRef<any>(null);
  const sessionRef = useRef(0);
  const intentionalStopRef = useRef(false);
  const hypRef = useRef(new HypothesisTracker());
  const restartTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const countdownTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const clearRestartTimer = useCallback(() => {
    if (restartTimerRef.current) {
      clearTimeout(restartTimerRef.current);
      restartTimerRef.current = null;
    }
  }, []);
  const clearCountdownTimer = useCallback(() => {
    if (countdownTimerRef.current) {
      clearInterval(countdownTimerRef.current);
      countdownTimerRef.current = null;
    }
  }, []);

  const stopRecognition = useCallback(() => {
    intentionalStopRef.current = true;
    sessionRef.current += 1; // invalidate late onresult events
    clearRestartTimer();
    hypRef.current.reset();
    const rec = recognitionRef.current;
    if (rec) {
      try {
        rec.onend = null;
        rec.onresult = null;
        rec.onerror = null;
      } catch {}
      try {
        rec.stop();
      } catch {}
      try {
        rec.abort?.();
      } catch {}
      recognitionRef.current = null;
    }
    setListening(false);
  }, [clearRestartTimer]);

  const startRecognition = useCallback((): boolean => {
    const SR: any =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SR) {
      alert(
        "이 브라우저는 음성 인식을 지원하지 않습니다.\nAndroid는 Chrome, iPhone은 Safari를 사용해 주세요.\n수동 모드로 전환합니다.",
      );
      setVoiceOn(false);
      voiceOnRef.current = false;
      return false;
    }
    try {
      const mySession = ++sessionRef.current;
      hypRef.current.reset();
      const rec = new SR();
      rec.continuous = true;
      rec.interimResults = true;
      rec.lang = langRef.current === "ko-mixed" ? "ko-KR" : langRef.current;

      rec.onstart = () => {
        if (mySession === sessionRef.current) setListening(true);
      };
      rec.onerror = (e: any) => {
        if (mySession !== sessionRef.current) return;
        if (e?.error === "not-allowed" || e?.error === "service-not-allowed") {
          alert("마이크 권한이 필요합니다. 브라우저 설정에서 마이크를 허용해 주세요.");
          setVoiceOn(false);
          voiceOnRef.current = false;
          intentionalStopRef.current = true;
          try {
            rec.stop();
          } catch {}
        }
      };
      rec.onend = () => {
        if (mySession !== sessionRef.current) return;
        setListening(false);
        if (!intentionalStopRef.current && playingRef.current && voiceOnRef.current) {
          // mobile Chrome ends the session periodically. Never reuse this run:
          // a fresh run restarts resultIndex at 0, so we need a new session id
          // and a fresh hypothesis tracker. The script position is preserved.
          try {
            rec.onresult = null;
            rec.onend = null;
            rec.onerror = null;
          } catch {}
          try {
            rec.abort?.();
          } catch {}
          if (recognitionRef.current === rec) recognitionRef.current = null;
          clearRestartTimer();
          restartTimerRef.current = setTimeout(() => {
            restartTimerRef.current = null;
            if (mySession === sessionRef.current && playingRef.current && voiceOnRef.current) {
              startRecognition(); // bumps session, resets HypothesisTracker
            }
          }, 120);
        }
      };

      rec.onresult = (ev: any) => {
        if (mySession !== sessionRef.current) return; // stale session
        if (!playingRef.current || !voiceOnRef.current) return;
        const items: Array<{ resultIndex: number; transcript: string; isFinal: boolean }> = [];
        for (let i = ev.resultIndex; i < ev.results.length; i++) {
          const r = ev.results[i];
          items.push({
            resultIndex: i,
            transcript: r?.[0]?.transcript ?? "",
            isFinal: !!r?.isFinal,
          });
        }
        const words = hypRef.current.push(items);
        if (!words) return; // duplicate interim/final for the same phrase
        const si = scriptIndexRef.current;
        const res = matchHypothesis(si, wordIndexRef.current, words, { back: 4, forward: 30 });
        if (res) advanceIndex(Math.min(res.index, si.words.length));
      };

      intentionalStopRef.current = false;
      recognitionRef.current = rec;
      try {
        rec.start();
      } catch {}
      return true;
    } catch (e) {
      console.error(e);
      return false;
    }
  }, [advanceIndex, clearRestartTimer]);

  // ---- rAF scroll loop ----
  useEffect(() => {
    let raf = 0;
    let lastT = performance.now();
    const loop = (t: number) => {
      const dt = Math.min(0.1, (t - lastT) / 1000);
      lastT = t;
      const el = scrollRef.current;
      if (el && playingRef.current) {
        if (voiceOnRef.current) {
          const h = el.clientHeight; // same coordinate space as offsetTop
          const els = wordElsRef.current;
          const idx = Math.max(0, Math.min(wordIndexRef.current, els.length - 1));
          const span = els[idx];
          let targetTop = el.scrollTop;
          if (span) {
            const raw = span.offsetTop - (linePosRef.current / 100) * h;
            const sinceMatch = (performance.now() - lastVoiceMatchTimeRef.current) / 1000;
            // bounded visual lead: 220ms of the measured reading speed, 0 before
            // speech starts and 0 once recognition goes stale
            const targetLead =
              sinceMatch < 1.5 ? Math.min(110, targetVelRef.current * 0.22) : 0;
            smoothedLeadRef.current +=
              (targetLead - smoothedLeadRef.current) * (1 - Math.exp(-dt * 4));
            targetTop = raw + smoothedLeadRef.current;
          }

          // never scroll backwards while voice tracking
          targetTop = Math.max(targetTop, maxTargetRef.current);
          maxTargetRef.current = targetTop;
          const cur = el.scrollTop;
          const diff = targetTop - cur;
          // frame-rate independent easing; catch up faster when far behind
          const rate = Math.abs(diff) > h * 0.3 ? 16 : 9;
          el.scrollTop =
            Math.abs(diff) < 0.5 ? targetTop : cur + diff * (1 - Math.exp(-dt * rate));
        } else {
          el.scrollTop = el.scrollTop + manualSpeedRef.current * dt;
        }
      }
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, []);

  // Start/stop handling with countdown
  const beginPlay = useCallback(() => {
    // Snapshot start position from current manual scroll: nearest word to the reading line
    const el = scrollRef.current;
    if (el) {
      const targetY = el.scrollTop + (linePosRef.current / 100) * el.clientHeight;
      let bestIdx = 0;
      let bestDist = Infinity;
      const arr = wordElsRef.current;
      for (let i = 0; i < arr.length; i++) {
        const s = arr[i];
        if (!s) continue;
        const d = Math.abs(s.offsetTop - targetY);
        if (d < bestDist) {
          bestDist = d;
          bestIdx = i;
        }
      }
      wordIndexRef.current = bestIdx;
      setWordIndex(bestIdx);
      maxTargetRef.current = el.scrollTop;
      smoothedLeadRef.current = 0;
      targetVelRef.current = 0;
    }
    clearCountdownTimer();
    setCountdown(3);
    let n = 3;
    countdownTimerRef.current = setInterval(() => {
      n -= 1;
      if (n <= 0) {
        clearCountdownTimer();
        setCountdown(null);
        setPlaying(true);
        playingRef.current = true;
        lastVoiceMatchTimeRef.current = performance.now();
        lastAdvanceTimeRef.current = performance.now();
        if (voiceOnRef.current) startRecognition();
      } else {
        setCountdown(n);
      }
    }, 800);
  }, [clearCountdownTimer, startRecognition]);

  const pausePlay = useCallback(() => {
    clearCountdownTimer();
    setCountdown(null);
    setPlaying(false);
    playingRef.current = false;
    stopRecognition();
  }, [clearCountdownTimer, stopRecognition]);

  const togglePlay = () => {
    if (playing || countdown !== null) pausePlay();
    else beginPlay();
  };

  const restart = () => {
    pausePlay();
    setWordIndex(0);
    wordIndexRef.current = 0;
    if (scrollRef.current) scrollRef.current.scrollTop = 0;
    wpsRef.current = 2.2;
    maxTargetRef.current = 0;
    smoothedLeadRef.current = 0;
    targetVelRef.current = 0;
    lastAdvanceTimeRef.current = 0;
  };

  const toggleVoice = () => {
    const next = !voiceOn;
    setVoiceOn(next);
    voiceOnRef.current = next;
    stopRecognition();
    if (playingRef.current && next) startRecognition();
  };

  // Restart recognition cleanly when the language changes mid-session
  useEffect(() => {
    if (!playingRef.current || !voiceOnRef.current) return;
    stopRecognition();
    const id = setTimeout(() => {
      if (playingRef.current && voiceOnRef.current) startRecognition();
    }, 120);
    return () => clearTimeout(id);
  }, [lang, startRecognition, stopRecognition]);

  // New script: reset tracking state (no stale closures / stale clamp)
  useEffect(() => {
    wordIndexRef.current = 0;
    setWordIndex(0);
    maxTargetRef.current = 0;
    smoothedLeadRef.current = 0;
    targetVelRef.current = 0;
    wpsRef.current = 2.2;
    hypRef.current.reset();
    if (scrollRef.current) scrollRef.current.scrollTop = 0;
  }, [scriptIndex]);

  // Layout changes move offsetTop: re-seat the monotonic clamp safely
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    maxTargetRef.current = el.scrollTop;
    targetVelRef.current = 0;
  }, [fontSize, linePos, mirror]);

  // Resize / orientation change: geometry estimates and clamp are invalid
  useEffect(() => {
    const onResize = () => {
      const el = scrollRef.current;
      if (el) maxTargetRef.current = el.scrollTop;
      targetVelRef.current = 0;
      smoothedLeadRef.current = 0;
    };
    window.addEventListener("resize", onResize);
    window.addEventListener("orientationchange", onResize);
    return () => {
      window.removeEventListener("resize", onResize);
      window.removeEventListener("orientationchange", onResize);
    };
  }, []);


  useEffect(() => {
    return () => {
      clearCountdownTimer();
      stopRecognition();
    };
  }, [clearCountdownTimer, stopRecognition]);

  // Word index into tokens (word-count so far up to token i)
  const currentTokWordIdx = wordIndex;

  const btnStyle: React.CSSProperties = {
    background: "#1a1a1a",
    color: "#e5e5e5",
    border: "1px solid #2a2a2a",
    borderRadius: 10,
    padding: "10px 12px",
    fontSize: 14,
    fontWeight: 600,
    fontFamily: "inherit",
    whiteSpace: "nowrap",
  };

  const handleCenterTap = (e: React.MouseEvent) => {
    // ignore clicks on interactive controls
    const target = e.target as HTMLElement;
    if (target.closest("[data-ctrl]")) return;
    setShowControls((s) => !s);
  };

  // Render tokens with wordIndex counter
  let wCounter = -1;
  return (
    <div
      style={{
        position: "fixed",
        inset: 0,
        background: "#000",
        color: "#d0d0d0",
        overflow: "hidden",
        fontFamily:
          '-apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Malgun Gothic", system-ui, sans-serif',
      }}
      onClick={handleCenterTap}
    >
      {/* Scroll container */}
      <div
        ref={scrollRef}
        style={{
          position: "absolute",
          inset: 0,
          overflowY: "auto",
          overflowX: "hidden",
          transform: mirror ? "scaleX(-1)" : undefined,
          paddingLeft: 22,
          paddingRight: 22,
          paddingTop: "45vh",
          paddingBottom: "70vh",
          scrollbarWidth: "none",
        }}
      >
        <div
          style={{
            fontSize,
            fontWeight: 800,
            lineHeight: 1.7,
            wordBreak: "keep-all",
            color: "#c8c8c8",
          }}
        >
          {blocks.map((b, bi) => {
            if (b.isEmpty) {
              return <p key={bi} style={{ margin: 0, height: "0.5em" }} />;

            }
            return (
              <p key={bi} style={{ margin: "0 0 0.5em 0" }}>
                {b.tokens.map((t, ti) => {
                  if (!t.isWord) {
                    return <span key={ti}>{t.text}</span>;
                  }
                  wCounter += 1;
                  const myW = wCounter;
                  let color = "#efefef";
                  if (myW < currentTokWordIdx) color = "#5a5a5a";
                  else if (myW === currentTokWordIdx) color = "#ffd23f";
                  return (
                    <span
                      key={ti}
                      ref={(el) => {
                        wordElsRef.current[myW] = el;
                      }}
                      style={{ color }}
                    >
                      {t.text}
                    </span>
                  );
                })}
              </p>
            );
          })}
        </div>
      </div>

      {/* Reading line marker */}
      <div
        style={{
          position: "absolute",
          top: `${linePos}%`,
          left: 0,
          right: 0,
          height: 2,
          background: "#ffd23f",
          opacity: 0.9,
          pointerEvents: "none",
          zIndex: 5,
        }}
      />
      <div
        style={{
          position: "absolute",
          top: `calc(${linePos}% - 10px)`,
          left: 4,
          color: "#ffd23f",
          fontSize: 18,
          fontWeight: 800,
          pointerEvents: "none",
          zIndex: 5,
        }}
      >
        ▶
      </div>

      {/* Mic indicator */}
      <div
        style={{
          position: "absolute",
          top: 14,
          right: 14,
          width: 12,
          height: 12,
          borderRadius: "50%",
          background: listening ? "#ff3b3b" : "#555",
          boxShadow: listening ? "0 0 0 0 rgba(255,59,59,0.7)" : "none",
          animation: listening ? "pulse 1.2s infinite" : undefined,
          zIndex: 6,
        }}
      />
      <style>{`
        @keyframes pulse {
          0% { box-shadow: 0 0 0 0 rgba(255,59,59,0.7); }
          70% { box-shadow: 0 0 0 12px rgba(255,59,59,0); }
          100% { box-shadow: 0 0 0 0 rgba(255,59,59,0); }
        }
        input[type=range] { accent-color: #ffd23f; }
        select { color: #e5e5e5; background: #1a1a1a; border: 1px solid #2a2a2a; border-radius: 8px; padding: 8px; font-family: inherit; }
      `}</style>

      {/* Top bar */}
      {showControls && (
        <div
          data-ctrl
          onClick={(e) => e.stopPropagation()}
          style={{
            position: "absolute",
            top: 0,
            left: 0,
            right: 0,
            padding: "12px 44px 12px 12px",
            display: "flex",
            gap: 8,
            alignItems: "center",
            background: "linear-gradient(to bottom, rgba(0,0,0,0.85), rgba(0,0,0,0))",
            zIndex: 7,
          }}
        >
          <button
            data-ctrl
            style={btnStyle}
            onClick={() => {
              pausePlay();
              onBack();
            }}
          >
            ← 목록
          </button>
          <button
            data-ctrl
            style={{
              ...btnStyle,
              background: mirror ? "#ffd23f" : "#1a1a1a",
              color: mirror ? "#000" : "#e5e5e5",
            }}
            onClick={() => setMirror((m) => !m)}
          >
            🪞 거울
          </button>
        </div>
      )}

      {/* Bottom bar */}
      {showControls && (
        <div
          data-ctrl
          onClick={(e) => e.stopPropagation()}
          style={{
            position: "absolute",
            bottom: 0,
            left: 0,
            right: 0,
            padding: "12px 12px 18px",
            background: "linear-gradient(to top, rgba(0,0,0,0.95), rgba(0,0,0,0))",
            zIndex: 7,
            display: "flex",
            flexDirection: "column",
            gap: 10,
          }}
        >
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>
            <button
              data-ctrl
              style={{
                ...btnStyle,
                background: voiceOn ? "#ffd23f" : "#1a1a1a",
                color: voiceOn ? "#000" : "#e5e5e5",
              }}
              onClick={toggleVoice}
            >
              🎙 음성추적 {voiceOn ? "ON" : "OFF"}
            </button>
            <select
              data-ctrl
              value={lang}
              onChange={(e) => setLang(e.target.value as Lang)}
            >
              <option value="ko-KR">한국어</option>
              <option value="ko-mixed">한영</option>
              <option value="en-US">English</option>
            </select>
            <button data-ctrl style={btnStyle} onClick={restart}>
              ⟲ 처음
            </button>
            <button
              data-ctrl
              style={{
                ...btnStyle,
                marginLeft: "auto",
                background: "#ffd23f",
                color: "#000",
                fontSize: 15,
                padding: "12px 18px",
              }}
              onClick={togglePlay}
            >
              {playing ? "⏸ 정지" : "▶ 시작"}
            </button>
          </div>

          <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
            <span style={{ fontSize: 12, color: "#888", width: 46 }}>글자 {fontSize}</span>
            <input
              data-ctrl
              type="range"
              min={24}
              max={90}
              value={fontSize}
              onChange={(e) => setFontSize(Number(e.target.value))}
              style={{ flex: 1 }}
            />
          </div>
          <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
            <span style={{ fontSize: 12, color: "#888", width: 46 }}>속도 {manualSpeed}</span>
            <input
              data-ctrl
              type="range"
              min={10}
              max={200}
              value={manualSpeed}
              onChange={(e) => setManualSpeed(Number(e.target.value))}
              style={{ flex: 1 }}
            />
          </div>
          <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
            <span style={{ fontSize: 12, color: "#888", width: 46 }}>위치 {linePos}</span>
            <input
              data-ctrl
              type="range"
              min={15}
              max={55}
              value={linePos}
              onChange={(e) => setLinePos(Number(e.target.value))}
              style={{ flex: 1 }}
            />
          </div>
        </div>
      )}

      {/* Countdown overlay */}
      {countdown !== null && (
        <div
          style={{
            position: "absolute",
            inset: 0,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            background: "rgba(0,0,0,0.55)",
            zIndex: 20,
            pointerEvents: "none",
          }}
        >
          <div
            style={{
              fontSize: 180,
              fontWeight: 900,
              color: "#ffd23f",
              lineHeight: 1,
            }}
          >
            {countdown}
          </div>
        </div>
      )}
    </div>
  );
}
