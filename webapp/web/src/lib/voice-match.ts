/**
 * 음성 인식 결과(hypothesis)를 대본 위치에 맞추는 매칭 로직.
 *
 * 한국어는 띄어쓰기가 인식 결과마다 달라지므로, 단어를 이어 붙인 "글자열" 위에서
 * 국소 정렬(Smith-Waterman)을 해서 대본의 어느 글자까지 읽었는지 찾고,
 * 그 글자가 속한 단어로 위치를 환산한다.
 */

const PUNCT = /[.,!?;:"'“”‘’()[\]<>{}…·~\-—/\\|=+*^%$#@&]/g;

export function normalizeWord(w: string): string {
  let s = w;
  try {
    s = s.normalize("NFKC");
  } catch {}
  return s.toLowerCase().replace(PUNCT, "").replace(/\s+/g, "").trim();
}

export function tokenize(input: string | string[]): string[] {
  return (Array.isArray(input) ? input : input.split(/\s+/))
    .map(normalizeWord)
    .filter((w) => w.length > 0);
}

export type ScriptIndex = {
  words: string[];
  /** 모든 단어를 공백 없이 이어 붙인 글자열 */
  chars: string;
  /** chars 의 각 글자가 속한 단어 번호 */
  charWord: Int32Array;
  /** 각 단어가 chars 에서 시작하는 위치 */
  wordStart: Int32Array;
  wordLen: Int32Array;
};

export function buildScriptIndex(words: string[]): ScriptIndex {
  const norm = words.map(normalizeWord);
  const wordStart = new Int32Array(norm.length);
  const wordLen = new Int32Array(norm.length);
  let chars = "";
  const charWord: number[] = [];
  for (let w = 0; w < norm.length; w++) {
    wordStart[w] = chars.length;
    wordLen[w] = norm[w].length;
    for (let k = 0; k < norm[w].length; k++) charWord.push(w);
    chars += norm[w];
  }
  return { words: norm, chars, charWord: Int32Array.from(charWord), wordStart, wordLen };
}

const MATCH = 2;
const MISMATCH = -1.2;
const GAP = -1.4;

type Alignment = { col: number; score: number; start: number };

/**
 * hyp(인식 글자열)와 text(대본 구간) 사이의 국소 정렬.
 * hyp 의 마지막 tailTolerance 글자 안에서 끝나는 정렬만 후보로 남긴다
 * (= 방금 말한 부분이 반드시 정렬에 포함되어야 함).
 * 반환: text 의 각 끝 위치(col, 1-based)별 최고 점수와 정렬 시작 위치.
 */
function localAlign(hyp: string, text: string, tailTolerance: number): Alignment[] {
  const n = hyp.length;
  const m = text.length;
  if (n === 0 || m === 0) return [];
  let prev = new Float32Array(m + 1);
  let cur = new Float32Array(m + 1);
  let prevStart = new Int32Array(m + 1).fill(-1);
  let curStart = new Int32Array(m + 1).fill(-1);
  const best = new Float32Array(m + 1).fill(0);
  const bestStart = new Int32Array(m + 1).fill(-1);
  const minRow = Math.max(1, n - Math.max(0, tailTolerance));

  for (let i = 1; i <= n; i++) {
    cur[0] = 0;
    curStart[0] = -1;
    const hc = hyp[i - 1];
    for (let j = 1; j <= m; j++) {
      let score = prev[j - 1] + (hc === text[j - 1] ? MATCH : MISMATCH);
      let start = prev[j - 1] > 0 ? prevStart[j - 1] : j - 1;
      const up = prev[j] + GAP;
      if (up > score) {
        score = up;
        start = prevStart[j];
      }
      const left = cur[j - 1] + GAP;
      if (left > score) {
        score = left;
        start = curStart[j - 1];
      }
      if (score <= 0) {
        score = 0;
        start = -1;
      }
      cur[j] = score;
      curStart[j] = start;
      if (i >= minRow && score > best[j]) {
        best[j] = score;
        bestStart[j] = start;
      }
    }
    const t = prev;
    prev = cur;
    cur = t;
    const ts = prevStart;
    prevStart = curStart;
    curStart = ts;
  }

  const out: Alignment[] = [];
  for (let j = 1; j <= m; j++) {
    if (best[j] > 0 && bestStart[j] >= 0) out.push({ col: j, score: best[j], start: bestStart[j] });
  }
  return out;
}

export type MatchOptions = {
  /** 현재 위치보다 몇 단어 뒤까지 탐색할지 */
  back?: number;
  /** 현재 위치보다 몇 단어 앞까지 탐색할지 */
  forward?: number;
  tailTolerance?: number;
  maxHypWords?: number;
  maxHypChars?: number;
  /**
   * 단어의 몇 %를 읽으면 다음 단어로 넘어갈지. 생략하면 원래 규칙
   * (4글자 이하는 전부, 그 이상은 80%). 낮출수록 빨리 넘어간다.
   */
  wordDoneRatio?: number;
};

export type MatchResult = {
  /** 다음에 읽을 단어 번호 */
  index: number;
  score: number;
  quality: number;
  alignedChars: number;
  jump: number;
};

export function matchHypothesis(
  si: ScriptIndex,
  current: number,
  hypWords: string[],
  opts: MatchOptions = {},
): MatchResult | null {
  const {
    back = 4,
    forward = 30,
    tailTolerance,
    maxHypWords = 10,
    maxHypChars = 44,
    wordDoneRatio,
  } = opts;
  const total = si.words.length;
  if (total === 0) return null;

  const words = hypWords.map(normalizeWord).filter(Boolean).slice(-maxHypWords);
  if (words.length === 0) return null;
  let hyp = words.join("");
  if (hyp.length > maxHypChars) hyp = hyp.slice(hyp.length - maxHypChars);
  if (hyp.length < 2) return null;

  const lastLen = words[words.length - 1].length;
  const tail = tailTolerance ?? Math.min(lastLen, 4);

  const fromWord = Math.max(0, Math.min(current, total - 1) - back);
  const toWord = Math.min(total - 1, Math.min(current, total - 1) + forward);
  const winStart = si.wordStart[fromWord];
  const winEnd = si.wordStart[toWord] + si.wordLen[toWord];
  const win = si.chars.slice(winStart, winEnd);
  if (win.length === 0) return null;

  const aligns = localAlign(hyp, win, tail);
  if (aligns.length === 0) return null;

  // 멀리 점프할수록 감점
  const cands = aligns.map((a) => {
    const charPos = winStart + a.col - 1;
    const word = si.charWord[charPos];
    const ahead = Math.max(0, word - current);
    const eff = a.score - 0.5 * Math.max(0, ahead - 2);
    return { ...a, eff, word };
  });
  let best: (typeof cands)[number] | null = null;
  for (const c of cands) if (!best || c.eff > best.eff) best = c;
  if (!best) return null;

  const alignedChars = best.col - best.start;
  if (alignedChars < 3) return null;
  const quality = best.score / (MATCH * alignedChars);

  // 단어를 (거의) 끝까지 읽었으면 다음 단어로
  const word = best.word;
  const readInWord = winStart + best.col - si.wordStart[word];
  const len = si.wordLen[word];
  const doneAt =
    wordDoneRatio === undefined
      ? len <= 4
        ? len
        : Math.ceil(len * 0.8)
      : len <= 1
        ? len
        : Math.max(1, Math.ceil(len * wordDoneRatio));
  let index = readInWord >= doneAt ? word + 1 : word;
  if (index > total) index = total;

  const jump = index - current;
  if (jump <= 0) return null;

  // 큰 점프는 다른 위치 후보와 확실히 구분될 때만
  if (jump > 6) {
    let rival = 0;
    for (const c of cands) if (Math.abs(c.word - best.word) > 3 && c.eff > rival) rival = c.eff;
    if (best.eff - rival < 2.5) return null;
  }
  if (jump > 12) {
    if (quality < 0.72 || alignedChars < 8) return null;
  } else if (quality < 0.5) return null;

  return { index, score: best.score, quality, alignedChars, jump };
}

type ResultItem = { resultIndex: number; transcript: string; isFinal: boolean };

/**
 * Web Speech API 의 results[] 를 누적해 최근 단어 목록을 만든다.
 * 같은 내용이 반복해서 들어오면 null 을 돌려 중복 매칭을 막는다.
 */
export class HypothesisTracker {
  finals = new Map<number, string>();
  interims = new Map<number, string>();
  lastConsumed = "";

  reset() {
    this.finals.clear();
    this.interims.clear();
    this.lastConsumed = "";
  }

  push(items: ResultItem[], maxWords = 10): string[] | null {
    for (const it of items) {
      if (it.isFinal) {
        this.finals.set(it.resultIndex, it.transcript);
        this.interims.delete(it.resultIndex);
      } else {
        this.interims.set(it.resultIndex, it.transcript);
      }
    }
    const keys = [...new Set([...this.finals.keys(), ...this.interims.keys()])].sort((a, b) => a - b);
    const parts: string[] = [];
    for (const k of keys) parts.push(this.interims.get(k) ?? this.finals.get(k) ?? "");
    const words = tokenize(parts.join(" ")).slice(-maxWords);
    if (words.length === 0) return null;
    const joined = words.join(" ");
    if (joined === this.lastConsumed) return null;
    this.lastConsumed = joined;
    return words;
  }
}
