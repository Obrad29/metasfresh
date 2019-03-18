package org.adempiere.impexp.inventory;

import static org.adempiere.model.InterfaceWrapperHelper.newInstance;
import static org.adempiere.model.InterfaceWrapperHelper.save;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.impexp.AbstractImportProcess;
import org.adempiere.impexp.IImportInterceptor;
import org.adempiere.mm.attributes.AttributeId;
import org.adempiere.mm.attributes.api.AttributeConstants;
import org.adempiere.mm.attributes.api.IAttributeDAO;
import org.adempiere.mm.attributes.api.IAttributeSetInstanceBL;
import org.adempiere.mm.attributes.api.ILotNumberDateAttributeDAO;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.util.lang.IMutable;
import org.compiere.model.I_I_Inventory;
import org.compiere.model.I_M_Attribute;
import org.compiere.model.I_M_AttributeInstance;
import org.compiere.model.I_M_AttributeSetInstance;
import org.compiere.model.I_M_AttributeValue;
import org.compiere.model.I_M_Inventory;
import org.compiere.model.I_M_InventoryLine;
import org.compiere.model.ModelValidationEngine;
import org.compiere.model.X_C_DocType;
import org.compiere.model.X_I_Inventory;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableMap;

import de.metas.document.DocTypeQuery;
import de.metas.document.IDocTypeDAO;
import de.metas.inventory.IInventoryBL;
import de.metas.logging.LogManager;
import de.metas.product.IProductBL;
import de.metas.product.ProductId;
import de.metas.util.Check;
import de.metas.util.Services;
import de.metas.util.time.SystemTime;
import lombok.NonNull;

/**
 * Import {@link I_I_Inventory} to {@link I_M_Inventory}.
 *
 */
public class InventoryImportProcess extends AbstractImportProcess<I_I_Inventory>
{
	private static final Logger logger = LogManager.getLogger(InventoryImportProcess.class);
	private final IProductBL productBL = Services.get(IProductBL.class);
	final IAttributeSetInstanceBL attributeSetInstanceBL = Services.get(IAttributeSetInstanceBL.class);
	final IAttributeDAO attributeDAO = Services.get(IAttributeDAO.class);
	final ILotNumberDateAttributeDAO lotNumberDateAttributeDAO = Services.get(ILotNumberDateAttributeDAO.class);

	@Override
	public Class<I_I_Inventory> getImportModelClass()
	{
		return I_I_Inventory.class;
	}

	@Override
	public String getImportTableName()
	{
		return I_I_Inventory.Table_Name;
	}

	@Override
	protected String getTargetTableName()
	{
		return I_M_Inventory.Table_Name;
	}

	@Override
	protected Map<String, Object> getImportTableDefaultValues()
	{
		return ImmutableMap.<String, Object> builder()
				.put(I_I_Inventory.COLUMNNAME_MovementDate, SystemTime.asDayTimestamp())
				.build();
	}

	@Override
	protected void updateAndValidateImportRecords()
	{
		MInventoryImportTableSqlUpdater.updateInventoryImportTable(getWhereClause());
	}

	@Override
	protected String getImportOrderBySql()
	{
		return I_I_Inventory.COLUMNNAME_WarehouseValue;
	}

	@Override
	protected I_I_Inventory retrieveImportRecord(final Properties ctx, final ResultSet rs) throws SQLException
	{
		return new X_I_Inventory(ctx, rs, ITrx.TRXNAME_ThreadInherited);
	}

	@Override
	protected ImportRecordResult importRecord(@NonNull final IMutable<Object> state,
			@NonNull final I_I_Inventory importRecord,
			final boolean isInsertOnly) throws Exception
	{
		//
		// Get previous values
		MInventoryImportContext context = (MInventoryImportContext)state.getValue();
		if (context == null)
		{
			context = new MInventoryImportContext();
			state.setValue(context);
		}
		final I_I_Inventory previousImportRecord = context.getPreviousImportRecord();
		final int previousMInventoryId = context.getPreviousM_Inventory_ID();
		final String previousWarehouseValue = context.getPreviousWarehouseValue();
		final Timestamp previousMovementDate = context.getPreviousMovementDate();
		context.setPreviousImportRecord(importRecord);

		final ImportRecordResult inventoryImportResult;

		final boolean firstImportRecordOrNewMInventory = previousImportRecord == null
				|| !Objects.equals(importRecord.getWarehouseValue(), previousWarehouseValue)
				|| !Objects.equals(importRecord.getMovementDate(), previousMovementDate);

		if (!firstImportRecordOrNewMInventory && isInsertOnly)
		{
			// #4994 do not update
			return ImportRecordResult.Nothing;
		}

		if (firstImportRecordOrNewMInventory)
		{
			// create a new list because we are passing to a new inventory
			context.clearPreviousRecordsForSameInventory();
			inventoryImportResult = importInventory(importRecord);
		}
		else
		{
			if (previousMInventoryId <= 0)
			{
				inventoryImportResult = importInventory(importRecord);
			}
			else if (importRecord.getM_Inventory_ID() <= 0 || importRecord.getM_Inventory_ID() == previousMInventoryId)
			{
				inventoryImportResult = doNothingAndUsePreviousInventory(importRecord, previousImportRecord);
			}
			else
			{
				throw new AdempiereException("Same value or movement date as previous line but not same Inventory linked");
			}
		}

		importInventoryLine(importRecord);
		context.collectImportRecordForSameInventory(importRecord);

		return inventoryImportResult;
	}

	private ImportRecordResult importInventory(@NonNull final I_I_Inventory importRecord)
	{
		final ImportRecordResult inventoryImportResult;
		inventoryImportResult = importRecord.getM_Inventory_ID() <= 0 ? ImportRecordResult.Inserted : ImportRecordResult.Updated;

		final I_M_Inventory inventory;
		if (importRecord.getM_Inventory_ID() <= 0)	// Insert new Inventory
		{
			inventory = createNewMInventory(importRecord);
		}
		else
		{
			inventory = importRecord.getM_Inventory();
		}

		ModelValidationEngine.get().fireImportValidate(this, importRecord, inventory, IImportInterceptor.TIMING_AFTER_IMPORT);
		InterfaceWrapperHelper.save(inventory);
		importRecord.setM_Inventory_ID(inventory.getM_Inventory_ID());
		return inventoryImportResult;
	}

	private I_M_Inventory createNewMInventory(@NonNull final I_I_Inventory importRecord)
	{
		final I_M_Inventory inventory;
		inventory = InterfaceWrapperHelper.create(getCtx(), I_M_Inventory.class, ITrx.TRXNAME_ThreadInherited);
		inventory.setAD_Org_ID(importRecord.getAD_Org_ID());
		inventory.setDescription("I " + importRecord.getM_Warehouse_ID() + " " + importRecord.getMovementDate());
		inventory.setC_DocType_ID(getDocTypeIdForInternalUseInventory(importRecord));
		inventory.setM_Warehouse_ID(importRecord.getM_Warehouse_ID());
		return inventory;
	}

	private int getDocTypeIdForInternalUseInventory(@NonNull final I_I_Inventory importRecord)
	{
		final IDocTypeDAO docTypeDAO = Services.get(IDocTypeDAO.class);

		final DocTypeQuery query = DocTypeQuery.builder()
				.docBaseType(X_C_DocType.DOCBASETYPE_MaterialPhysicalInventory)
				.adClientId(importRecord.getAD_Client_ID())
				.adOrgId(importRecord.getAD_Org_ID())
				.build();
		return docTypeDAO.getDocTypeId(query).getRepoId();
	}

	/**
	 * reuse previous inventory
	 *
	 * @param importRecord
	 * @param previousImportRecord
	 * @return
	 */
	private ImportRecordResult doNothingAndUsePreviousInventory(@NonNull final I_I_Inventory importRecord, @NonNull final I_I_Inventory previousImportRecord)
	{
		importRecord.setM_Inventory_ID(previousImportRecord.getM_Inventory_ID());
		return ImportRecordResult.Nothing;
	}

	public I_M_InventoryLine importInventoryLine(@NonNull final I_I_Inventory importRecord)
	{
		final I_M_Inventory inventory = importRecord.getM_Inventory();

		I_M_InventoryLine inventoryLine = importRecord.getM_InventoryLine();
		if (inventoryLine != null)
		{
			if (inventoryLine.getM_Inventory_ID() <= 0)
			{
				inventoryLine.setM_Inventory(inventory);
			}
			else if (inventoryLine.getM_Inventory_ID() != inventory.getM_Inventory_ID())
			{
				throw new AdempiereException("Inventory of Inventory Line <> Inventory");
			}

			inventoryLine.setM_Locator(importRecord.getM_Locator());
			inventoryLine.setM_Product(importRecord.getM_Product());
			inventoryLine.setQtyCount(importRecord.getQtyCount());
			inventoryLine.setIsCounted(true);

			ModelValidationEngine.get().fireImportValidate(this, importRecord, inventoryLine, IImportInterceptor.TIMING_AFTER_IMPORT);
			InterfaceWrapperHelper.save(inventoryLine);
		}
		else
		{
			final int M_AttributeSetInstance_ID = extractM_AttributeSetInstance_ID(importRecord);
			inventoryLine = InterfaceWrapperHelper.create(getCtx(), I_M_InventoryLine.class, ITrx.TRXNAME_ThreadInherited);
			inventoryLine.setQtyCount(importRecord.getQtyCount());
			inventoryLine.setM_Inventory(inventory);
			inventoryLine.setM_Locator(importRecord.getM_Locator());
			inventoryLine.setM_Product(importRecord.getM_Product());
			inventoryLine.setC_UOM(importRecord.getM_Product().getC_UOM());
			inventoryLine.setM_AttributeSetInstance_ID(M_AttributeSetInstance_ID);
			final int chargeId = Services.get(IInventoryBL.class).getDefaultInternalChargeId();
			inventoryLine.setC_Charge_ID(chargeId);
			inventoryLine.setIsCounted(true);

			ModelValidationEngine.get().fireImportValidate(this, importRecord, inventoryLine, IImportInterceptor.TIMING_AFTER_IMPORT);
			InterfaceWrapperHelper.save(inventoryLine);
			logger.trace("Insert inventory line - {}", inventoryLine);

			importRecord.setM_InventoryLine_ID(inventoryLine.getM_InventoryLine_ID());
		}

		return inventoryLine;
	}

	private int extractM_AttributeSetInstance_ID(@NonNull final I_I_Inventory importRecord)
	{
		int M_AttributeSetInstance_ID = 0;
		final ProductId productId = ProductId.ofRepoId(importRecord.getM_Product_ID());
		if (productBL.isInstanceAttribute(productId))
		{
			final I_M_AttributeSetInstance asi = attributeSetInstanceBL.createASI(productId);

			// lot
			if (!Check.isEmpty(importRecord.getLot(), true))
			{
				final AttributeId lotNumberAttrId = lotNumberDateAttributeDAO.getLotNumberAttributeId();
				attributeSetInstanceBL.setAttributeInstanceValue(asi, lotNumberAttrId, importRecord.getLot());
			}
			//
			// BestBeforeDate
			if (importRecord.getHU_BestBeforeDate() != null)
			{
				final I_M_Attribute bestBeforeDateAttr = attributeDAO.retrieveAttributeByValue(AttributeConstants.ATTR_BestBeforeDate);
				attributeSetInstanceBL.setAttributeInstanceValue(asi, bestBeforeDateAttr, importRecord.getHU_BestBeforeDate());
			}
			//
			// TE
			if (!Check.isEmpty(importRecord.getTE(), true))
			{
				final I_M_Attribute TEAttr = attributeDAO.retrieveAttributeByValue(AttributeConstants.ATTR_TE);
				attributeSetInstanceBL.setAttributeInstanceValue(asi, TEAttr, importRecord.getTE());
			}
			//
			// DateReceived
			if (importRecord.getDateReceived() != null)
			{
				final I_M_Attribute dateReceivedAttr = attributeDAO.retrieveAttributeByValue(AttributeConstants.ATTR_DateReceived);
				attributeSetInstanceBL.setAttributeInstanceValue(asi, dateReceivedAttr, importRecord.getDateReceived());
			}
			//
			// SubProducerBPartner_Value
			if (!Check.isEmpty(importRecord.getSubProducerBPartner_Value(), true))
			{
				final I_M_Attribute subProducerBPartnettr = attributeDAO.retrieveAttributeByValue(AttributeConstants.ATTR_SubProducerBPartner_Value);
				final I_M_AttributeValue subProducerBPartneValue = getOrCreateSubproducerAttributeValue(subProducerBPartnettr, importRecord);
				getCreateAttributeInstanceForSubproducer(asi, subProducerBPartneValue);
			}
			attributeSetInstanceBL.setDescription(asi);
			InterfaceWrapperHelper.save(asi);

			M_AttributeSetInstance_ID = asi.getM_AttributeSetInstance_ID();
		}

		return M_AttributeSetInstance_ID;
	}

	private I_M_AttributeValue getOrCreateSubproducerAttributeValue(@NonNull final I_M_Attribute attribute, @NonNull final I_I_Inventory importRecord)
	{
		I_M_AttributeValue attributeValue = Services.get(IAttributeDAO.class).retrieveAttributeValueOrNull(attribute, String.valueOf(importRecord.getSubProducer_BPartner_ID()));
		if (attributeValue != null)
		{
			return attributeValue;
		}

		attributeValue = InterfaceWrapperHelper.newInstance(I_M_AttributeValue.class);
		attributeValue.setM_Attribute(attribute);
		attributeValue.setValue(String.valueOf(importRecord.getSubProducer_BPartner_ID()));
		attributeValue.setName(importRecord.getSubProducerBPartner_Value());
		attributeValue.setIsActive(true);
		InterfaceWrapperHelper.save(attributeValue);
		return attributeValue;
	}

	private I_M_AttributeInstance getCreateAttributeInstanceForSubproducer(final I_M_AttributeSetInstance asi, final I_M_AttributeValue attributeValue)
	{
		Check.assumeNotNull(attributeValue, "attributeValue not null");

		// services
		final IAttributeDAO attributeDAO = Services.get(IAttributeDAO.class);

		// M_Attribute_ID
		final AttributeId attributeId = AttributeId.ofRepoId(attributeValue.getM_Attribute_ID());

		//
		// Get/Create/Update Attribute Instance
		I_M_AttributeInstance attributeInstance = attributeDAO.retrieveAttributeInstance(asi, attributeId);
		if (attributeInstance == null)
		{
			attributeInstance = newInstance(I_M_AttributeInstance.class, asi);
		}
		attributeInstance.setM_AttributeSetInstance(asi);
		attributeInstance.setM_AttributeValue(attributeValue);
		attributeInstance.setM_Attribute_ID(attributeId.getRepoId());
		// the attribute is a list, but expect to store as number, the id of the partner
		attributeInstance.setValueNumber(new BigDecimal(attributeValue.getValue()));
		save(attributeInstance);

		return attributeInstance;
	}

}
