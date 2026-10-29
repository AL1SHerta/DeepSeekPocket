package com.ybdsj.deeppocket.domain.billing

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEvaluatorTest {

    private fun bd(value: String) = BigDecimal(value)

    @Test
    fun `no threshold never notifies`() {
        val decision = AlertEvaluator.evaluate(
            total = bd("1.00"),
            threshold = null,
            alreadySuppressed = false,
        )
        assertFalse(decision.shouldNotify)
        assertFalse(decision.nextSuppressed)
    }

    @Test
    fun `zero or negative threshold is treated as disabled`() {
        listOf("0", "-5").forEach { raw ->
            val decision = AlertEvaluator.evaluate(
                total = bd("0.00"),
                threshold = bd(raw),
                alreadySuppressed = true,
            )
            assertFalse(decision.shouldNotify)
            assertFalse(decision.nextSuppressed)
        }
    }

    @Test
    fun `unknown balance keeps suppression state and stays silent`() {
        val decision = AlertEvaluator.evaluate(
            total = null,
            threshold = bd("10"),
            alreadySuppressed = true,
        )
        assertFalse(decision.shouldNotify)
        assertTrue(decision.nextSuppressed)
    }

    @Test
    fun `below threshold notifies once`() {
        val first = AlertEvaluator.evaluate(
            total = bd("3.20"),
            threshold = bd("10"),
            alreadySuppressed = false,
        )
        assertTrue(first.shouldNotify)
        assertTrue(first.nextSuppressed)

        // 第二次仍然低位：不重复打扰
        val second = AlertEvaluator.evaluate(
            total = bd("3.10"),
            threshold = bd("10"),
            alreadySuppressed = first.nextSuppressed,
        )
        assertFalse(second.shouldNotify)
        assertTrue(second.nextSuppressed)
    }

    @Test
    fun `recovering above threshold resets suppression`() {
        val decision = AlertEvaluator.evaluate(
            total = bd("12.00"),
            threshold = bd("10"),
            alreadySuppressed = true,
        )
        assertFalse(decision.shouldNotify)
        assertFalse(decision.nextSuppressed)
    }

    @Test
    fun `equal to threshold is not below`() {
        val decision = AlertEvaluator.evaluate(
            total = bd("10.00"),
            threshold = bd("10"),
            alreadySuppressed = false,
        )
        assertFalse(decision.shouldNotify)
        assertFalse(decision.nextSuppressed)
    }

    @Test
    fun `dropping again after recovery notifies again`() {
        // 先跌破 → 提醒
        val drop1 = AlertEvaluator.evaluate(bd("8"), bd("10"), alreadySuppressed = false)
        assertTrue(drop1.shouldNotify)

        // 充值后回到阈值以上 → 复位
        val recover = AlertEvaluator.evaluate(bd("50"), bd("10"), drop1.nextSuppressed)
        assertFalse(recover.nextSuppressed)

        // 再次跌破 → 重新提醒
        val drop2 = AlertEvaluator.evaluate(bd("9"), bd("10"), recover.nextSuppressed)
        assertTrue(drop2.shouldNotify)
        assertEquals(true, drop2.nextSuppressed)
    }
}
