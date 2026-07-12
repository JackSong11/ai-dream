package com.example.dream.service.core.chat.retriever.nlp;

import java.util.regex.Pattern;

/**
 * 英文归一化（对应 RagFlow infinity.rag_tokenizer 的 {@code _normalize_token}）。
 *
 * <p>RagFlow 对纯字母 token 的处理为：先 {@code WordNetLemmatizer.lemmatize} 再
 * {@code SnowballStemmer("english").stem}。Java 侧无 NLTK：
 * <ul>
 *   <li>词形还原（lemmatize）：WordNet 依赖大型词库，此处以轻量规则近似常见名词复数/动词变位还原；</li>
 *   <li>词干化（stem）：实现 Snowball(Porter2) English stemmer 的主体步骤，覆盖绝大多数英文词。</li>
 * </ul>
 * 非纯字母 token（{@code [a-zA-Z_-]+$} 不匹配）原样返回，与 RagFlow 一致。</p>
 *
 * @author dream
 */
public final class EnglishNormalizer {

    private static final Pattern ALPHA = Pattern.compile("[a-zA-Z_-]+$");

    private EnglishNormalizer() {
    }

    /**
     * 归一化单个 token（对应 _normalize_token）。
     */
    public static String normalize(String t) {
        if (t == null || t.isEmpty()) {
            return t;
        }
        if (!ALPHA.matcher(t).matches()) {
            return t;
        }
        return stem(lemmatize(t));
    }

    /**
     * 轻量词形还原（近似 WordNetLemmatizer 对名词/动词的常见规则，默认名词优先）。
     */
    private static String lemmatize(String w) {
        String s = w;
        int len = s.length();
        // 复数 -ies -> -y
        if (len > 4 && s.endsWith("ies")) {
            return s.substring(0, len - 3) + "y";
        }
        // -ves -> -f/-fe（近似，取 -f）
        if (len > 3 && s.endsWith("ves")) {
            return s.substring(0, len - 3) + "f";
        }
        // -ses/-xes/-zes/-ches/-shes -> 去 es
        if (len > 4 && (s.endsWith("ses") || s.endsWith("xes") || s.endsWith("zes")
                || s.endsWith("ches") || s.endsWith("shes"))) {
            return s.substring(0, len - 2);
        }
        // 一般复数 -s（排除 -ss / -us / -is）
        if (len > 3 && s.endsWith("s") && !s.endsWith("ss") && !s.endsWith("us") && !s.endsWith("is")) {
            return s.substring(0, len - 1);
        }
        return s;
    }

    // ==================== Snowball / Porter2 English stemmer ====================

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y';
    }

    /**
     * Porter2 词干化主体（覆盖常见英文；对短词/含大写词做小写化后处理）。
     */
    public static String stem(String word) {
        if (word == null) {
            return null;
        }
        String w = word.toLowerCase();
        if (w.length() <= 2) {
            return w;
      }
        // Step 0: 去除起始撇号（Java 侧一般无）
        // 计算 R1、R2
        int r1 = computeRegion(w);
        int r2 = computeRegion(w.substring(Math.min(r1, w.length())));
        r2 = r1 + r2;

        StringBuilder sb = new StringBuilder(w);

        // Step 1a
        if (endsWith(sb, "sses")) {
            replaceEnd(sb, 4, "ss");
        } else if (endsWith(sb, "ied") || endsWith(sb, "ies")) {
            int n = sb.length();
            replaceEnd(sb, 3, sb.length() - 3 > 1 ? "i" : "ie");
            if (n - 3 <= 1) {
      // handled above
            }
        } else if (endsWith(sb, "ss") || endsWith(sb, "us")) {
            // do nothing
        } else if (endsWith(sb, "s")) {
            // 若 s 前有非紧邻元音则去掉 s
            if (containsVowelBefore(sb, sb.length() - 2)) {
                sb.deleteCharAt(sb.length() - 1);
            }
        }

        // Step 1b
        if (endsWith(sb, "eed") || endsWith(sb, "eedly")) {
            int suf = endsWith(sb, "eedly") ? 5 : 3;
            if (inRegion(sb.length() - suf, r1)) {
                replaceEnd(sb, suf, "ee");
            }
        } else {
            String[] sfx = {"ed", "edly", "ing", "ingly"};
            for (String s : sfx) {
                if (endsWith(sb, s)) {
                    if (hasVowelInStem(sb, sb.length() - s.length())) {
                        sb.setLength(sb.length() - s.length());
                        if (endsWith(sb, "at") || endsWith(sb, "bl") || endsWith(sb, "iz")) {
                            sb.append("e");
                        } else if (isDoubleConsonant(sb)) {
                            sb.deleteCharAt(sb.length() - 1);
                        } else if (isShort(sb, r1)) {
                            sb.append("e");
                        }
                    }
                    break;
                }
            }
        }

        // Step 1c: y/Y -> i（前面是辅音且词长>2）
        if ((endsWith(sb, "y") || endsWith(sb, "Y")) && sb.length() > 2
                && !isVowel(sb.charAt(sb.length() - 2))) {
            sb.setCharAt(sb.length() - 1, 'i');
        }

        // Step 2
        String[][] step2 = {
                {"ational", "ate"}, {"tional", "tion"}, {"enci", "ence"}, {"anci", "ance"},
                {"izer", "ize"}, {"ization", "ize"}, {"ation", "ate"}, {"ator", "ate"},
                {"alism", "al"}, {"aliti", "al"}, {"alli", "al"}, {"fulness", "ful"},
                {"ousli", "ous"}, {"ousness", "ous"}, {"iveness", "ive"}, {"iviti", "ive"},
                {"biliti", "ble"}, {"bli", "ble"}, {"fulli", "ful"}, {"lessli", "less"},
                {"entli", "ent"}, {"ogi", "og"}, {"li", ""}
        };
        for (String[] p : step2) {
            if (endsWith(sb, p[0]) && inRegion(sb.length() - p[0].length(), r1)) {
                if (p[0].equals("li")) {
                    char before = sb.length() >= 3 ? sb.charAt(sb.length() - 3) : ' ';
                    if ("cdeghkmnrt".indexOf(before) < 0) {
                        break;
                    }
                }
                replaceEnd(sb, p[0].length(), p[1]);
                break;
            }
        }

        // Step 3
        String[][] step3 = {
                {"ational", "ate"}, {"tional", "tion"}, {"alize", "al"}, {"icate", "ic"},
                {"iciti", "ic"}, {"ical", "ic"}, {"ful", ""}, {"ness", ""}
        };
        for (String[] p : step3) {
            if (endsWith(sb, p[0]) && inRegion(sb.length() - p[0].length(), r1)) {
                replaceEnd(sb, p[0].length(), p[1]);
                break;
            }
        }

        // Step 4
        String[] step4 = {"al", "ance", "ence", "er", "ic", "able", "ible", "ant", "ement",
                "ment", "ent", "ism", "ate", "iti", "ous", "ive", "ize"};
        for (String s : step4) {
            if (endsWith(sb, s) && inRegion(sb.length() - s.length(), r2)) {
                sb.setLength(sb.length() - s.length());
                break;
            }
        }
        if (endsWith(sb, "ion") && inRegion(sb.length() - 3, r2)
                && sb.length() >= 4 && (sb.charAt(sb.length() - 4) == 's' || sb.charAt(sb.length() - 4) == 't')) {
            sb.setLength(sb.length() - 3);
        }

        // Step 5
        if (endsWith(sb, "e")) {
            int loc = sb.length() - 1;
            if (inRegion(loc, r2) || (inRegion(loc, r1) && !isShortSyllable(sb, sb.length() - 2))) {
                sb.deleteCharAt(sb.length() - 1);
            }
        } else if (endsWith(sb, "l") && sb.length() >= 2 && sb.charAt(sb.length() - 2) == 'l'
                && inRegion(sb.length() - 1, r2)) {
            sb.deleteCharAt(sb.length() - 1);
        }

        return sb.toString();
    }

    private static int computeRegion(String w) {
        for (int i = 1; i < w.length(); i++) {
            if (!isVowel(w.charAt(i)) && isVowel(w.charAt(i - 1))) {
                return i + 1;
            }
        }
        return w.length();
    }

    private static boolean endsWith(StringBuilder sb, String s) {
        int n = sb.length(), m = s.length();
        if (n < m) {
            return false;
        }
        for (int i = 0; i < m; i++) {
            if (sb.charAt(n - m + i) != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static void replaceEnd(StringBuilder sb, int suffixLen, String repl) {
        sb.setLength(sb.length() - suffixLen);
        sb.append(repl);
    }

    private static boolean inRegion(int idx, int rStart) {
        return idx >= rStart;
    }

    private static boolean containsVowelBefore(StringBuilder sb, int idx) {
        for (int i = 0; i < idx; i++) {
            if (isVowel(sb.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasVowelInStem(StringBuilder sb, int stemLen) {
        for (int i = 0; i < stemLen; i++) {
            if (isVowel(sb.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDoubleConsonant(StringBuilder sb) {
        int n = sb.length();
        if (n < 2) {
            return false;
        }
        char a = sb.charAt(n - 1);
        char b = sb.charAt(n - 2);
        return a == b && !isVowel(a) && "bdfgmnprt".indexOf(a) >= 0;
    }

    private static boolean isShortSyllable(StringBuilder sb, int idx) {
        if (idx < 1) {
            return false;
        }
        char c = sb.charAt(idx);
        char prev = sb.charAt(idx - 1);
        if (isVowel(c) || !isVowel(prev)) {
            return false;
        }
        return "wxy".indexOf(c) < 0;
    }

    private static boolean isShort(StringBuilder sb, int r1) {
        return isShortSyllable(sb, sb.length() - 1) && r1 >= sb.length();
    }
}