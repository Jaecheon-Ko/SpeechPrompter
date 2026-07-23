package com.prompter.app.matcher

/**
 * 인식된 음성 텍스트를 대본과 퍼지 매칭하여 "지금 어디를 읽고 있는지"를 추정한다.
 *
 * 원리:
 *  - 대본을 정규화(소문자화, 한글/영문/숫자만 유지)하고, 정규화 문자 → 원본 char offset 매핑을 유지
 *  - 최근 인식된 텍스트의 꼬리(tail) N자를 대본의 탐색 윈도우와 문자 바이그램 Dice 유사도로 비교
 *  - 가장 유사한 지점을 현재 위치로 갱신 (기본적으로 앞으로만 진행, 소폭 뒤로는 허용)
 *
 * ASR이 100% 정확하지 않아도 위치 추정에는 충분하다.
 */
class ScriptMatcher(script: String) {

    /** 정규화된 대본 문자열 */
    private val norm: String

    /** norm[i] 가 원본 script의 몇 번째 char에서 왔는지 */
    private val normToOrig: IntArray

    /** 현재 위치 (norm 기준 인덱스, "여기까지 읽음") */
    var currentNormPos = 0
        private set

    val normLength: Int get() = norm.length

    init {
        val sb = StringBuilder()
        val map = ArrayList<Int>()
        script.forEachIndexed { i, c ->
            val n = normalizeChar(c)
            if (n != null) {
                sb.append(n)
                map.add(i)
            }
        }
        norm = sb.toString()
        normToOrig = map.toIntArray()
    }

    /** 원본 대본에서의 현재 char offset (하이라이트/스크롤용) */
    fun currentOriginalOffset(): Int {
        if (normToOrig.isEmpty()) return 0
        val idx = currentNormPos.coerceIn(0, normToOrig.size - 1)
        return normToOrig[idx] + 1
    }

    /** 수동 리셋 (처음부터 / 특정 지점부터) */
    fun reset(normPos: Int = 0) {
        currentNormPos = normPos.coerceIn(0, norm.length)
    }

    /**
     * 새 인식 결과를 반영하고, 위치가 갱신되었으면 true.
     * @param recognized ASR이 내놓은 최근 발화 텍스트 (부분 결과 포함 가능)
     */
    fun update(recognized: String): Boolean {
        val tail = normalize(recognized).takeLast(TAIL_LEN)
        if (tail.length < MIN_TAIL) return false

        // 탐색 윈도우: 현재 위치에서 약간 뒤 ~ 충분히 앞
        val start = (currentNormPos - BACKTRACK).coerceAtLeast(0)
        val end = (currentNormPos + LOOKAHEAD).coerceAtMost(norm.length)
        if (end - start < tail.length) return false

        val tailGrams = bigrams(tail)
        if (tailGrams.isEmpty()) return false

        var bestPos = -1
        var bestScore = 0.0
        // 후보: tail과 같은 길이의 대본 조각을 1자씩 밀며 비교
        var candGrams = bigramCounts(norm, start, start + tail.length)
        var pos = start + tail.length
        while (pos <= end) {
            val score = dice(tailGrams, candGrams)
            if (score > bestScore) {
                bestScore = score
                bestPos = pos
            }
            if (pos == end) break
            // 슬라이딩: 앞 바이그램 제거, 뒤 바이그램 추가
            slide(candGrams, norm, pos - tail.length, pos)
            pos++
        }

        if (bestScore >= THRESHOLD && bestPos > currentNormPos - BACKTRACK) {
            currentNormPos = bestPos
            return true
        }
        return false
    }

    // ---------- 내부 유틸 ----------

    private fun normalizeChar(c: Char): Char? {
        val lc = c.lowercaseChar()
        return when {
            lc in 'a'..'z' || lc in '0'..'9' -> lc
            lc in '가'..'힣' -> lc
            else -> null // 공백/문장부호/특수문자 제거
        }
    }

    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        s.forEach { c -> normalizeChar(c)?.let(sb::append) }
        return sb.toString()
    }

    private fun bigrams(s: String): HashMap<Int, Int> {
        val m = HashMap<Int, Int>()
        for (i in 0 until s.length - 1) {
            val key = s[i].code * 65536 + s[i + 1].code
            m[key] = (m[key] ?: 0) + 1
        }
        return m
    }

    private fun bigramCounts(s: String, from: Int, to: Int): HashMap<Int, Int> {
        val m = HashMap<Int, Int>()
        for (i in from until to - 1) {
            val key = s[i].code * 65536 + s[i + 1].code
            m[key] = (m[key] ?: 0) + 1
        }
        return m
    }

    private fun slide(m: HashMap<Int, Int>, s: String, oldStart: Int, newEnd: Int) {
        // 제거: (oldStart, oldStart+1)
        val rk = s[oldStart].code * 65536 + s[oldStart + 1].code
        val rv = m[rk]
        if (rv != null) { if (rv <= 1) m.remove(rk) else m[rk] = rv - 1 }
        // 추가: (newEnd-1, newEnd)
        val ak = s[newEnd - 1].code * 65536 + s[newEnd].code
        m[ak] = (m[ak] ?: 0) + 1
    }

    private fun dice(a: Map<Int, Int>, b: Map<Int, Int>): Double {
        var inter = 0
        var sa = 0
        var sb = 0
        a.forEach { (k, v) -> sa += v; b[k]?.let { inter += minOf(v, it) } }
        b.forEach { (_, v) -> sb += v }
        if (sa + sb == 0) return 0.0
        return 2.0 * inter / (sa + sb)
    }

    companion object {
        /** 매칭에 사용할 인식 텍스트 꼬리 길이 (정규화 문자 수) */
        private const val TAIL_LEN = 24
        private const val MIN_TAIL = 6
        /** 현재 위치보다 얼마나 뒤까지 되돌아갈 수 있나 */
        private const val BACKTRACK = 12
        /** 앞으로 얼마나 멀리까지 탐색하나 (건너뛰며 읽기 대응) */
        private const val LOOKAHEAD = 260
        /** 이 유사도 미만이면 위치 갱신 안 함 */
        private const val THRESHOLD = 0.44
    }
}
