package com.teleflip.app.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TelegramStarsCheckoutTest {
    private lateinit var manager: TelegramManager
    private val requests = mutableListOf<TdApi.Function<*>>()
    private var paymentResponse: TdApi.Object = TdApi.PaymentResult(true, "")

    @Before fun setUp() {
        manager = TelegramManager(ApplicationProvider.getApplicationContext<Context>())
        val client = mock(Client::class.java)
        manager.setTdClientForTesting(client)
        doAnswer { invocation ->
            val request = invocation.getArgument<TdApi.Function<*>>(0)
            val handler = invocation.getArgument<Client.ResultHandler>(1)
            requests.add(request)
            when (request) {
                is TdApi.GetPaymentForm -> handler.onResult(TdApi.PaymentForm(123L, TdApi.PaymentFormTypeStars(25), 0, null))
                is TdApi.SendPaymentForm -> handler.onResult(paymentResponse)
                is TdApi.GetMessage -> handler.onResult(TdApi.Error(404, "Not cached yet"))
            }
            null
        }.`when`(client).send(any(), any())
    }

    @Test fun quotedServerPriceRequiresSeparateConfirmationAndPaysOnlyOnce() {
        var quote: TelegramManager.PaidMediaQuote? = null
        manager.quotePaidMedia(10, 20) { result, _ -> quote = result }
        assertEquals(25L, quote?.stars)
        assertTrue(requests.none { it is TdApi.SendPaymentForm })
        var paid = false
        manager.purchasePaidMedia(quote!!) { result, _ -> paid = result }
        assertTrue(paid)
        manager.purchasePaidMedia(quote!!) { result, _ -> assertFalse(result) }
        val payment = requests.filterIsInstance<TdApi.SendPaymentForm>().single()
        assertEquals(123L, payment.paymentFormId)
        val invoice = payment.inputInvoice as TdApi.InputInvoiceMessage
        assertEquals(10L, invoice.chatId)
        assertEquals(20L, invoice.messageId)
    }

    @Test fun serverErrorOrVerificationDoesNotClaimPaymentSucceeded() {
        for (response in listOf(TdApi.Error(400, "BALANCE_TOO_LOW"), TdApi.PaymentResult(false, "https://example.com/verify"))) {
            paymentResponse = response
            manager.quotePaidMedia(10, 20) { quote, _ ->
                manager.purchasePaidMedia(quote!!) { paid, error ->
                    assertFalse(paid)
                    assertTrue(!error.isNullOrBlank())
                }
            }
        }
        assertTrue(requests.none { it is TdApi.GetMessage })
    }
}
