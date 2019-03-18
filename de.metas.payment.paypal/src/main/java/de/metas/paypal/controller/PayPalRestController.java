package de.metas.paypal.controller;

import com.paypal.api.payments.*;
import com.paypal.base.rest.APIContext;
import com.paypal.base.rest.PayPalRESTException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/*
 * #%L
 * de.metas.paypal.controller
 * %%
 * Copyright (C) 2019 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
@RestController
@RequestMapping(PayPalRestEndpoint.ENDPOINT)
public class PayPalRestController implements PayPalRestEndpoint
{
	private static final String CLIENT_ID = "ATOk-R9ufyjporznZqFkcXdaiLfxon6bwD-DrT271-cz79j-b4S15AOoor6sQZ70PvrEATWuX-WeE_X-";
	private static final String CLIENT_SECRET = "EM7laJWeQK7EvJvrkYDvSKpNvorlMHKiLAE5N2oqDdqqXJNiKYGQCRXKMA6OU_peZHwL_eGQwhRdFvrH";
	private static final String EXECUTION_MODE = "sandbox";

	private Payment capturePayment()
	{
		Payer payer = new Payer();
		payer.setPaymentMethod("paypal");

		RedirectUrls redirectUrls = new RedirectUrls();
		redirectUrls.setCancelUrl("http://localhost:3000/cancel");
		redirectUrls.setReturnUrl("http://localhost:3000/return");

		Details details = new Details();
		details.setShipping("7.27");
		details.setSubtotal("5.53");
		details.setTax("1.16");

		// Set Payment amount
		Amount amount = new Amount();
		amount.setCurrency("USD");
		amount.setTotal("13.96");
		amount.setDetails(details);

		Transaction transaction = new Transaction();
		transaction.setAmount(amount);
		transaction.setDescription("PayPal transaction");
		List<Transaction> transactions = new ArrayList<>();
		transactions.add(transaction);

		Payment payment = new Payment();

		payment.setIntent("authorize");
		payment.setPayer(payer);
		payment.setTransactions(transactions);
		payment.setRedirectUrls(redirectUrls);

		APIContext apiContext = new APIContext(CLIENT_ID, CLIENT_SECRET, EXECUTION_MODE);

		try
		{

			Payment myPayment = payment.create(apiContext);

			System.out.println("createdPayment " + myPayment.toString());
			payment.setId(myPayment.getId());

			PaymentExecution paymentExecution = new PaymentExecution();
			paymentExecution.setPayerId("123");

			Payment createdAuthPayment = payment.execute(apiContext, paymentExecution);

			Authorization authorization = createdAuthPayment.getTransactions().get(0).getRelatedResources().get(0).getAuthorization();

			log("Authorization ID" + authorization.getId());

		}
		catch (PayPalRESTException e)
		{
			System.err.println(e.getDetails());
		}
		return payment;
	}

	private void log(String string)
	{
		System.out.println(string);

	}

	public Payment capturePayPalPayment()
	{
		return capturePayment();
	}

	public final static void main(String[] args)
	{
		PayPalRestController controller = new PayPalRestController();
		controller.capturePayment();
	}
}
