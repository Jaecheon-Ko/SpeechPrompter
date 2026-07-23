package com.prompter.app.matcher

/** 대본의 한 단어: 원본 문자 오프셋 + 정규화 텍스트 */
class WordRef(val start: Int, val end: Int, val norm: String)

/**
 * 웹 버전에서 이식한 단어 기반 위치 매처.
 *
 * - 인식 텍스트의 최근 최대 8단어 사용
 * - 탐색 범위: curIdx-4 ~ curIdx+25
 * - 점수: t번째 인식 단어 일치 시 +(t+1) — 최근 단어에 가중
 * - 채택 조건(오점프 방지): 마지막 인식 단어 필수 일치 / 5단어 초과 전진 시
 *   2개 이상 일치 / 15단어 초과 전진 시 최대점수의 70%, 그 외 45% / 전진만 허용
 */
class WordMatcher(script: String) {

    val words: List<WordRef> = tokenize(script)

    var curIdx = 0
        private set

    fun reset(idx: Int = 0) {
        curIdx = if (words.isEmpty()) 0 else idx.coerceIn(0, words.size - 1)
    }

    /**
     * @param mixed 한영 모드 — 로마자 단어에 대해 부분일치 허용
     * @return 위치가 전진했으면 true
     */
    fun update(recognized: String, mixed: Boolean): Boolean {
        if (words.isEmpty()) return false
        val rec = recognized.split(WS).map(::normWord).filter { it.isNotEmpty() }.takeLast(8)
        if (rec.isEmpty()) return false

        val n = rec.size
        val maxScore = n * (n + 1) / 2
        val from = (curIdx - 4).coerceAtLeast(0)
        val to = (curIdx + 25).coerceAtMost(words.size - 1)

        var bestJ = -1
        var bestScore = 0
        var bestCount = 0

        for (j in from..to) {
            // 가장 최근 인식 단어가 반드시 일치할 것
            if (!wordsMatch(rec[n - 1], words[j].norm, mixed)) continue
            var score = n // t = n-1 → +(t+1) = n
            var count = 1
            var si = j - 1
            // 나머지 인식 단어를 뒤에서부터 정렬 (대본 쪽 최대 2단어 건너뛰기 허용)
            for (t in n - 2 downTo 0) {
                if (si < 0) break
                var found = -1
                var k = si
                var skips = 0
                while (k >= 0 && skips <= 2) {
                    if (wordsMatch(rec[t], words[k].norm, mixed)) { found = k; break }
                    k--; skips++
                }
                if (found >= 0) {
                    score += t + 1
                    count++
                    si = found - 1
                }
            }
            if (score > bestScore || (score == bestScore && j > bestJ)) {
                bestScore = score; bestJ = j; bestCount = count
            }
        }

        if (bestJ < 0) return false
        val jump = bestJ - curIdx
        if (jump <= 0) return false                          // 전진만 허용
        if (jump > 5 && bestCount < 2) return false          // 큰 점프엔 근거 2개 이상
        val need = if (jump > 15) 0.70 else 0.45
        if (bestScore < maxScore * need) return false

        curIdx = bestJ
        return true
    }

    companion object {
        private val WS = Regex("\\s+")
        private val PUNCT = Regex("[.,!?;:\"'\u201C\u201D\u2018\u2019()\\[\\]\u2026\u00B7~\\-\u2014/\\\\]")

        fun normWord(s: String): String = s.lowercase().replace(PUNCT, "")

        fun wordsMatch(a: String, b: String, mixed: Boolean): Boolean {
            if (a == b) return true
            // 한국어 조사·어미 변형 흡수
            if (a.length >= 2 && b.length >= 2 && (a.startsWith(b) || b.startsWith(a))) return true
            // 한영 모드: 로마자 포함 3자 이상이면 부분일치 허용
            if (mixed && a.length >= 3 && b.length >= 3 &&
                a.any { it in 'a'..'z' } && b.any { it in 'a'..'z' }
            ) return a.contains(b) || b.contains(a)
            return false
        }

        private fun tokenize(s: String): List<WordRef> {
            val list = ArrayList<WordRef>()
            var i = 0
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) break
                val start = i
                while (i < s.length && !s[i].isWhitespace()) i++
                val norm = normWord(s.substring(start, i))
                if (norm.isNotEmpty()) list.add(WordRef(start, i, norm))
            }
            return list
        }
    }
}
