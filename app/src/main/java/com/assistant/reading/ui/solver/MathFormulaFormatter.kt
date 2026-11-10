package com.assistant.reading.ui.solver

/**
 * 理科数学公式与符号排版格式化引擎
 * 将大模型输出的原始 LaTeX 公式和排版标记，转化为中学生友好、标准规范的 Unicode 数学排版
 */
object MathFormulaFormatter {

    fun formatMathContent(rawContent: String): String {
        if (rawContent.isBlank()) return ""

        var text = rawContent

        // 1. 块级公式与行内公式包裹符号清理
        text = text.replace(Regex("""\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)) { match ->
            "\n" + match.groupValues[1].trim() + "\n"
        }
        text = text.replace(Regex("""\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)) { match ->
            "\n" + match.groupValues[1].trim() + "\n"
        }
        text = text.replace(Regex("""\\\((.*?)\\\)""")) { match ->
            " " + match.groupValues[1].trim() + " "
        }
        text = text.replace(Regex("""\$(.*?)\$""")) { match ->
            " " + match.groupValues[1].trim() + " "
        }

        // 2. 文本修饰与无意义排版宏去除
        text = text.replace(Regex("""\\(?:text|mathrm|mathbf|boldsymbol)\{([^}]*)\}""")) { it.groupValues[1] }
        text = text.replace(Regex("""\\(?:quad|qquad|;|,)"""), " ")
        text = text.replace(Regex("""\\left\s*([(\[{|])""")) { it.groupValues[1] }
        text = text.replace(Regex("""\\right\s*([)\]}|])""")) { it.groupValues[1] }
        text = text.replace(Regex("""\\left\."""), "")
        text = text.replace(Regex("""\\right\."""), "")

        // 3. 分数转换 (\frac{a}{b} -> (a)/(b) 或 a/b)
        text = text.replace(Regex("""\\(?:frac|dfrac)\{([^{}]+)\}\{([^{}]+)\}""")) { match ->
            val numerator = match.groupValues[1].trim()
            val denominator = match.groupValues[2].trim()
            if (numerator.length <= 3 && denominator.length <= 3 && !numerator.contains(" ") && !denominator.contains(" ")) {
                "$numerator/$denominator"
            } else {
                "($numerator)/($denominator)"
            }
        }
        // 处理一次嵌套分数
        text = text.replace(Regex("""\\(?:frac|dfrac)\{([^{}]+)\}\{([^{}]+)\}""")) { match ->
            val num = match.groupValues[1].trim()
            val den = match.groupValues[2].trim()
            "($num)/($den)"
        }

        // 4. 根号转换
        text = text.replace(Regex("""\\sqrt\[3\]\{([^{}]+)\}""")) { "³√(${it.groupValues[1].trim()})" }
        text = text.replace(Regex("""\\sqrt\{([^{}]+)\}""")) {
            val inner = it.groupValues[1].trim()
            if (inner.length <= 3) "√$inner" else "√($inner)"
        }

        // 5. 上下标智能转换 (常用数字与字母转为原生 Unicode 上下标)
        // 上标
        val superscriptMap = mapOf(
            '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
            '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
            '+' to '⁺', '-' to '⁻', 'n' to 'ⁿ', 'x' to 'ˣ', 'y' to 'ʸ'
        )
        text = text.replace(Regex("""\^\{([0-9nxy+\-]+)\}""")) { match ->
            match.groupValues[1].map { superscriptMap[it] ?: it }.joinToString("")
        }
        text = text.replace(Regex("""\^([0-9nxy+\-])""")) { match ->
            superscriptMap[match.groupValues[1][0]]?.toString() ?: match.value
        }

        // 下标
        val subscriptMap = mapOf(
            '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
            '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
            '+' to '₊', '-' to '₋', 'n' to 'ₙ', 'i' to 'ᵢ', 'x' to 'ₓ', 'y' to 'ᵧ'
        )
        text = text.replace(Regex("""_\{([0-9nixy+\-]+)\}""")) { match ->
            match.groupValues[1].map { subscriptMap[it] ?: it }.joinToString("")
        }
        text = text.replace(Regex("""_([0-9nixy+\-])""")) { match ->
            subscriptMap[match.groupValues[1][0]]?.toString() ?: match.value
        }

        // 6. 常见数学运算符号替换
        val operatorMap = listOf(
            """\\times""" to " × ",
            """\\div""" to " ÷ ",
            """\\cdot""" to " · ",
            """\\pm""" to " ± ",
            """\\mp""" to " ∓ ",
            """\\le(?!q)""" to " ≤ ",
            """\\leq""" to " ≤ ",
            """\\ge(?!q)""" to " ≥ ",
            """\\geq""" to " ≥ ",
            """\\ne(?!q)""" to " ≠ ",
            """\\neq""" to " ≠ ",
            """\\approx""" to " ≈ ",
            """\\equiv""" to " ≡ ",
            """\\sim""" to " ～ ",
            """\\propto""" to " ∝ ",
            """\\infty""" to "∞",
            """\\in""" to " ∈ ",
            """\\notin""" to " ∉ ",
            """\\subset""" to " ⊂ ",
            """\\subseteq""" to " ⊆ ",
            """\\cap""" to " ∩ ",
            """\\cup""" to " ∪ ",
            """\\forall""" to "∀",
            """\\exists""" to "∃",
            """\\because""" to "∵",
            """\\therefore""" to "∴"
        )
        for ((pattern, rep) in operatorMap) {
            text = text.replace(Regex(pattern), rep)
        }

        // 7. 几何与角度符号替换
        val geometryMap = listOf(
            """\\triangle""" to "△",
            """\\angle""" to "∠",
            """\\perp""" to "⊥",
            """\\parallel""" to "∥",
            """\\odot""" to "⊙",
            """\^\\circ""" to "°",
            """\\degree""" to "°"
        )
        for ((pattern, rep) in geometryMap) {
            text = text.replace(Regex(pattern), rep)
        }

        // 8. 常见希腊字母替换
        val greekMap = listOf(
            """\\alpha""" to "α",
            """\\beta""" to "β",
            """\\gamma""" to "γ",
            """\\delta""" to "δ",
            """\\epsilon""" to "ε",
            """\\theta""" to "θ",
            """\\lambda""" to "λ",
            """\\mu""" to "μ",
            """\\pi""" to "π",
            """\\rho""" to "ρ",
            """\\sigma""" to "σ",
            """\\phi""" to "φ",
            """\\omega""" to "ω",
            """\\Delta""" to "Δ",
            """\\Sigma""" to "Σ",
            """\\Omega""" to "Ω"
        )
        for ((pattern, rep) in greekMap) {
            text = text.replace(Regex(pattern), rep)
        }

        // 9. 三角函数与对数函数前缀去除斜杠
        text = text.replace(Regex("""\\(sin|cos|tan|cot|sec|csc|log|ln|lg|lim|max|min)""")) {
            it.groupValues[1] + " "
        }

        // 10. 规范化连续多余空格与换行
        text = text.replace(Regex("""[ \t]+"""), " ")
        text = text.replace(Regex("""\n{3,}"""), "\n\n")

        return text.trim()
    }
}
