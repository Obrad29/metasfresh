package de.metas.contracts.refund;

import static java.math.BigDecimal.ONE;
import static java.math.BigDecimal.TEN;
import static java.math.BigDecimal.ZERO;
import static org.adempiere.model.InterfaceWrapperHelper.newInstance;
import static org.adempiere.model.InterfaceWrapperHelper.saveRecord;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.adempiere.test.AdempiereTestHelper;
import org.adempiere.util.collections.CollectionUtils;
import org.compiere.util.TimeUtil;
import org.junit.Before;
import org.junit.Test;

import de.metas.contracts.ConditionsId;
import de.metas.contracts.FlatrateTermId;
import de.metas.contracts.model.I_C_Flatrate_RefundConfig;
import de.metas.contracts.model.I_C_Flatrate_Term;
import de.metas.contracts.model.I_C_Invoice_Candidate_Assignment;
import de.metas.contracts.model.X_C_Flatrate_RefundConfig;
import de.metas.contracts.model.X_C_Flatrate_Term;
import de.metas.contracts.refund.RefundConfig.RefundBase;
import de.metas.contracts.refund.RefundConfig.RefundMode;
import de.metas.invoice.InvoiceSchedule;
import de.metas.invoice.InvoiceSchedule.Frequency;
import de.metas.invoice.InvoiceScheduleRepository;
import de.metas.invoicecandidate.model.I_C_Invoice_Candidate;
import de.metas.money.CurrencyRepository;
import de.metas.money.MoneyService;
import lombok.NonNull;

/*
 * #%L
 * de.metas.contracts
 * %%
 * Copyright (C) 2018 metas GmbH
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

public class RefundInvoiceCandidateServiceTest
{
	private static final BigDecimal FIFTEEN = new BigDecimal("15");

	private static final LocalDate NOW = LocalDate.now();

	private static final BigDecimal FIVE = new BigDecimal("5");

	private RefundInvoiceCandidateService refundInvoiceCandidateService;

	private RefundTestTools refundTestTools;

	private RefundContractRepository refundContractRepository;

	private List<I_C_Flatrate_RefundConfig> refundConfigRecords;

	private ConditionsId conditionsId;

	private RefundConfigRepository refundConfigRepository;

	private RefundInvoiceCandidateRepository refundInvoiceCandidateRepository;

	private AssignableInvoiceCandidateRepository assignableInvoiceCandidateRepository;

	@Before
	public void init()
	{
		AdempiereTestHelper.get().init();

		refundTestTools = new RefundTestTools(); // this also makes sure we have the ILCandHandler and C_DocType needed to create a new refund candidate

		refundConfigRepository = new RefundConfigRepository(new InvoiceScheduleRepository());
		refundContractRepository = new RefundContractRepository(refundConfigRepository);

		final RefundInvoiceCandidateFactory refundInvoiceCandidateFactory = new RefundInvoiceCandidateFactory(refundContractRepository);

		refundInvoiceCandidateRepository = new RefundInvoiceCandidateRepository(refundContractRepository, refundInvoiceCandidateFactory);

		final MoneyService moneyService = new MoneyService(new CurrencyRepository());

		refundInvoiceCandidateService = new RefundInvoiceCandidateService(
				refundInvoiceCandidateRepository,
				refundInvoiceCandidateFactory,
				moneyService);

		conditionsId = ConditionsId.ofRepoId(20);
		refundConfigRecords = createAndVerifyBaseRefundconfigs(conditionsId);

		assignableInvoiceCandidateRepository = new AssignableInvoiceCandidateRepository(new AssignableInvoiceCandidateFactory());

		final InvoiceScheduleRepository invoiceScheduleRepository = new InvoiceScheduleRepository();

		invoiceScheduleRepository.save(InvoiceSchedule
				.builder()
				.frequency(Frequency.DAILY)
				.build());

	}

	private List<I_C_Flatrate_RefundConfig> createAndVerifyBaseRefundconfigs(@NonNull final ConditionsId conditionsId)
	{
		final List<I_C_Flatrate_RefundConfig> refundConfigRecords = RefundConfigRepositoryTest.createThreeRefundConfigRecords(conditionsId);

		assertThat(refundConfigRecords.get(0).getMinQty()).isZero(); // guard

		assertThat(refundConfigRecords).hasSize(3); // guard

		final I_C_Flatrate_RefundConfig secondConfigRecord = refundConfigRecords.get(1);
		assertThat(secondConfigRecord.getMinQty()).isLessThanOrEqualTo(TEN); // guard; this is the config record that shall match the candidate's quantity

		return refundConfigRecords;
	}

	private void updateRefundConfigRecords(@NonNull final RefundMode refundMode)
	{
		if (RefundMode.PER_INDIVIDUAL_SCALE.equals(refundMode))
		{
			refundConfigRecords.get(0).setRefundMode(X_C_Flatrate_RefundConfig.REFUNDMODE_PerScale);
			saveRecord(refundConfigRecords.get(0));
			refundConfigRecords.get(1).setRefundMode(X_C_Flatrate_RefundConfig.REFUNDMODE_PerScale);
			saveRecord(refundConfigRecords.get(1));
			refundConfigRecords.get(2).setRefundMode(X_C_Flatrate_RefundConfig.REFUNDMODE_PerScale);
			saveRecord(refundConfigRecords.get(2));
		}
		else
		{
			refundConfigRecords.get(0).setRefundMode(X_C_Flatrate_RefundConfig.REFUNDMODE_Accumulated);
			saveRecord(refundConfigRecords.get(0));
			refundConfigRecords.get(1).setRefundMode(X_C_Flatrate_RefundConfig.REFUNDMODE_Accumulated);
			saveRecord(refundConfigRecords.get(1));
			refundConfigRecords.get(2).setRefundMode(X_C_Flatrate_RefundConfig.REFUNDMODE_Accumulated);
			saveRecord(refundConfigRecords.get(2));
		}
	}

	@Test
	public void retrieveOrCreateMatchingCandidate_create()
	{
		updateRefundConfigRecords(RefundMode.ALL_MAX_SCALE);
		retrieveOrCreateMatchingCandidate_create_performTest();
	}

	private RefundInvoiceCandidate retrieveOrCreateMatchingCandidate_create_performTest()
	{
		final I_C_Invoice_Candidate assignableRecord = AssignableInvoiceCandidateRepositoryTest.createAssignableCandidateRecord(refundTestTools);
		final AssignableInvoiceCandidate assignableCandidate = assignableInvoiceCandidateRepository.ofRecord(assignableRecord);
		assertThat(assignableCandidate.getQuantity().getAsBigDecimal()).isEqualByComparingTo(FIFTEEN); // guard

		final I_C_Flatrate_Term contractRecord = newInstance(I_C_Flatrate_Term.class);
		contractRecord.setBill_BPartner_ID(RefundTestTools.BPARTNER_ID.getRepoId());
		contractRecord.setType_Conditions(X_C_Flatrate_Term.TYPE_CONDITIONS_Refund);
		contractRecord.setC_Flatrate_Conditions_ID(conditionsId.getRepoId());
		contractRecord.setStartDate(TimeUtil.asTimestamp(NOW));
		contractRecord.setEndDate(TimeUtil.asTimestamp(NOW.plusDays(10)));
		contractRecord.setM_Product_ID(assignableRecord.getM_Product_ID());
		saveRecord(contractRecord);

		final FlatrateTermId contractId = FlatrateTermId.ofRepoId(contractRecord.getC_Flatrate_Term_ID());
		final RefundContract refundContract = refundContractRepository.getById(contractId);

		// invoke the method under test
		final List<RefundInvoiceCandidate> result = refundInvoiceCandidateService.retrieveOrCreateMatchingRefundCandidates(assignableCandidate, refundContract);

		assertThat(result).hasSize(1);
		final RefundInvoiceCandidate resultElement = result.get(0);

		assertThat(resultElement.getRefundContract().getId()).isEqualTo(contractId);

		final I_C_Flatrate_RefundConfig secondConfigRecord = refundConfigRecords.get(1);

		final RefundConfig resultConfig = resultElement.getRefundConfig();
		assertThat(resultConfig.getConditionsId().getRepoId()).isEqualTo(secondConfigRecord.getC_Flatrate_Conditions_ID());
		assertThat(resultConfig.getMinQty()).isEqualByComparingTo(secondConfigRecord.getMinQty());
		assertThat(resultConfig.getPercent().getValueAsBigDecimal()).isEqualByComparingTo(secondConfigRecord.getRefundPercent());

		return resultElement;
	}

	@Test
	public void retrieveOrCreateMatchingCandidate_retrieve()
	{
		final AssignableInvoiceCandidate assignableCandidate = refundTestTools.createAssignableCandidateWithAssignment();

		final RefundInvoiceCandidate refundInvoiceCandidate = assignableCandidate
				.getAssignmentsToRefundCandidates()
				.get(0)
				.getRefundInvoiceCandidate();

		// invoke the method under test
		final List<RefundInvoiceCandidate> result = refundInvoiceCandidateService.retrieveOrCreateMatchingRefundCandidates(
				assignableCandidate,
				refundInvoiceCandidate.getRefundContract());

		assertThat(result).hasSize(1);
		final RefundInvoiceCandidate resultElement = result.get(0);

		assertThat(resultElement).isEqualTo(refundInvoiceCandidate);
	}

	@Test
	public void retrieveOrCreateMatchingCandidate_create_perScaleConfig()
	{
		retrieveOrCreateMatchingCandidate_create_perScaleConfig_performTest();
	}

	private List<RefundInvoiceCandidate> retrieveOrCreateMatchingCandidate_create_perScaleConfig_performTest()
	{
		// make sure there is already one refund record
		updateRefundConfigRecords(RefundMode.ALL_MAX_SCALE);
		final RefundInvoiceCandidate existingRefundCandidate = retrieveOrCreateMatchingCandidate_create_performTest();

		updateRefundConfigRecords(RefundMode.PER_INDIVIDUAL_SCALE); // for the actual rest, we change the config
		final I_C_Invoice_Candidate assignableRecord = AssignableInvoiceCandidateRepositoryTest.createAssignableCandidateRecord(refundTestTools);
		assignableRecord.setQtyInvoiced(TEN);
		assignableRecord.setQtyToInvoice(FIVE);
		saveRecord(assignableRecord);

		final AssignableInvoiceCandidate assignableCandidate = assignableInvoiceCandidateRepository.ofRecord(assignableRecord);
		assertThat(assignableCandidate.getQuantity().getAsBigDecimal()).isEqualByComparingTo(FIFTEEN); // guard;

		final FlatrateTermId contractId = existingRefundCandidate.getRefundContract().getId();
		final RefundContract refundContract = refundContractRepository.getById(contractId);

		// invoke the method under test
		final List<RefundInvoiceCandidate> result = refundInvoiceCandidateService.retrieveOrCreateMatchingRefundCandidates(assignableCandidate, refundContract);

		assertThat(result).hasSize(2);

		final RefundInvoiceCandidate firstResultElement = result.get(0);
		final I_C_Flatrate_RefundConfig firstConfigRecord = refundConfigRecords.get(0);

		assertThat(firstResultElement.getRefundContract().getId()).isEqualTo(contractId);
		assertThat(firstResultElement.getId()).isEqualTo(existingRefundCandidate.getId());
		assertThat(firstResultElement.getRefundConfig().getId().getRepoId()).isEqualTo(firstConfigRecord.getC_Flatrate_RefundConfig_ID());

		final RefundConfig firstResultConfig = firstResultElement.getRefundConfig();
		assertThat(firstResultConfig.getConditionsId().getRepoId()).isEqualTo(firstConfigRecord.getC_Flatrate_Conditions_ID());
		assertThat(firstResultConfig.getMinQty()).isEqualByComparingTo(firstConfigRecord.getMinQty());
		assertThat(firstResultConfig.getPercent().getValueAsBigDecimal()).isEqualByComparingTo(firstConfigRecord.getRefundPercent());

		final RefundInvoiceCandidate secondResultElement = result.get(1);
		final I_C_Flatrate_RefundConfig secondConfigRecord = refundConfigRecords.get(1);

		assertThat(secondResultElement.getId()).isNotEqualTo(existingRefundCandidate.getId());
		assertThat(secondResultElement.getRefundContract().getId()).isEqualTo(contractId);
		assertThat(secondResultElement.getRefundConfig().getId().getRepoId()).isEqualTo(secondConfigRecord.getC_Flatrate_RefundConfig_ID());

		final RefundConfig secondResultConfig = secondResultElement.getRefundConfig();
		assertThat(secondResultConfig.getConditionsId().getRepoId()).isEqualTo(secondConfigRecord.getC_Flatrate_Conditions_ID());
		assertThat(secondResultConfig.getMinQty()).isEqualByComparingTo(secondConfigRecord.getMinQty());
		assertThat(secondResultConfig.getPercent().getValueAsBigDecimal()).isEqualByComparingTo(secondConfigRecord.getRefundPercent());

		return result;
	}

	/**
	 * Invoke {@link #retrieveOrCreateMatchingCandidate_create_perScaleConfig_performTest()} to make sure that
	 * there are already two refund candidates that match our first two scales (minQty 0 and 10).
	 *
	 * Assign 5 to the first refund candidate and 10 to the second.
	 *
	 * Invoke the method under test with an assignable candidate that has a quantity of 15
	 * => expect a 3rd refund candidate to be created, for the 3rd scale with minQty=16.
	 */
	@Test
	public void retrieveOrCreateMatchingCandidate_create_perScaleConfig2()
	{
		// make sure there are already two refund candidates that match "our" first two scales
		final List<RefundInvoiceCandidate> perScaleRefundCandidates = retrieveOrCreateMatchingCandidate_create_perScaleConfig_performTest();

		// set up assignment records; this is usually done elsewhere; we need them in place when loading/retrieving those existing records
		final I_C_Invoice_Candidate_Assignment firstAssigmentRecord = newInstance(I_C_Invoice_Candidate_Assignment.class);
		firstAssigmentRecord.setC_Invoice_Candidate_Term_ID(perScaleRefundCandidates.get(0).getId().getRepoId());
		firstAssigmentRecord.setC_Flatrate_RefundConfig_ID(perScaleRefundCandidates.get(0).getRefundConfig().getId().getRepoId());
		firstAssigmentRecord.setAssignedQuantity(FIVE);
		saveRecord(firstAssigmentRecord);

		final I_C_Invoice_Candidate_Assignment secondAssigmentRecord = newInstance(I_C_Invoice_Candidate_Assignment.class);
		secondAssigmentRecord.setC_Invoice_Candidate_Term_ID(perScaleRefundCandidates.get(1).getId().getRepoId());
		secondAssigmentRecord.setC_Flatrate_RefundConfig_ID(perScaleRefundCandidates.get(1).getRefundConfig().getId().getRepoId());
		secondAssigmentRecord.setAssignedQuantity(TEN);
		saveRecord(secondAssigmentRecord);

		final RefundContract contract = CollectionUtils.extractSingleElement(perScaleRefundCandidates, RefundInvoiceCandidate::getRefundContract);

		final I_C_Invoice_Candidate assignableRecord = AssignableInvoiceCandidateRepositoryTest.createAssignableCandidateRecord(refundTestTools);
		assignableRecord.setQtyInvoiced(TEN);
		assignableRecord.setQtyToInvoice(FIVE);
		saveRecord(assignableRecord);

		final AssignableInvoiceCandidate assignableCandidate = assignableInvoiceCandidateRepository.ofRecord(assignableRecord);
		assertThat(assignableCandidate.getQuantity().getAsBigDecimal()).isEqualByComparingTo(FIFTEEN); // guard;

		// invoke the method under test
		final List<RefundInvoiceCandidate> result = refundInvoiceCandidateService.retrieveOrCreateMatchingRefundCandidates(assignableCandidate, contract);

		assertThat(result).hasSize(3);
		final RefundInvoiceCandidate firstResultElement = result.get(0);
		assertThat(firstResultElement.getId()).isEqualTo(perScaleRefundCandidates.get(0).getId());
		assertThat(firstResultElement.getRefundConfig().getMinQty()).isEqualByComparingTo("0");

		final RefundInvoiceCandidate secondResultElement = result.get(1);
		assertThat(secondResultElement.getId()).isEqualTo(perScaleRefundCandidates.get(1).getId());
		assertThat(secondResultElement.getRefundConfig().getMinQty()).isEqualByComparingTo("10");

		final RefundInvoiceCandidate thirdResultElement = result.get(2);
		assertThat(thirdResultElement.getId()).isNotIn(perScaleRefundCandidates.get(0).getId(), perScaleRefundCandidates.get(1).getId());
		assertThat(thirdResultElement.getRefundConfig().getMinQty()).isEqualByComparingTo("16");
	}

	@Test
	public void retrieveOrCreateMatchingCandidate_create2_perScaleConfig()
	{
		// TODO:
		// existing per-scale configs with 0 and 10
		// assignable candidate with 15
		// expected:
		// * two refund candidates, both assigned to the assignable candidate with 15
	}

	/**
	 * Assignable candidate with quantity = 10; refund config with 20%
	 */
	@Test
	public void addAssignableMoney()
	{
		final RefundInvoiceCandidate refundCandidate = refundTestTools.createRefundCandidate();
		assertThat(refundCandidate.getMoney().getValue()).isEqualByComparingTo("100"); // guard
		assertThat(refundCandidate.getAssignedQuantity().getAsBigDecimal()).isEqualByComparingTo(ZERO); // guard

		final AssignableInvoiceCandidate assignableCandidate = refundTestTools.createAssignableCandidateStandlone();
		assertThat(assignableCandidate.getQuantity().getAsBigDecimal()).isEqualByComparingTo(ONE);// guard
		assertThat(assignableCandidate.getMoney().getValue()).isEqualByComparingTo(TEN);// guard

		assertThat(refundCandidate.getRefundConfig().getRefundBase()).isEqualTo(RefundBase.PERCENTAGE);
		assertThat(refundCandidate.getRefundConfig().getPercent().getValueAsBigDecimal()).isEqualTo("20");

		// invoke the method under test
		final AssignmentToRefundCandidate result = refundInvoiceCandidateService.addAssignableMoney(refundCandidate, assignableCandidate);

		assertThat(result.getRefundInvoiceCandidate().getId()).isEqualTo(refundCandidate.getId());
		assertThat(result.getMoneyAssignedToRefundCandidate().getValue()).isEqualByComparingTo("2");
		assertThat(result.getRefundInvoiceCandidate().getMoney().getValue()).isEqualByComparingTo("102");
	}
}
