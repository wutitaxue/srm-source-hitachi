package org.srm.source.rfx.domain.service.impl;

import com.alibaba.fastjson.JSON;
import io.choerodon.core.exception.CommonException;
import org.apache.commons.collections.CollectionUtils;
import org.hzero.core.base.BaseConstants;
import org.hzero.mybatis.domian.Condition;
import org.hzero.mybatis.util.Sqls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.srm.boot.adaptor.client.AdaptorTaskHelper;
import org.srm.boot.adaptor.client.exception.TaskNotExistException;
import org.srm.boot.adaptor.client.result.TaskResultBox;
import org.srm.boot.platform.configcenter.CnfHelper;
import org.srm.common.TenantInfoHelper;
import org.srm.source.rfx.api.dto.RfxItemUpdateBeforeDTO;
import org.srm.source.rfx.domain.entity.*;
import org.srm.source.rfx.domain.repository.QuotationDetailRepository;
import org.srm.source.rfx.domain.repository.QuotationDimensionRepository;
import org.srm.source.rfx.domain.repository.RfxLineItemRepository;
import org.srm.source.rfx.infra.constant.SourceConstants;
import org.srm.source.share.domain.entity.SourceTemplate;
import org.srm.source.share.domain.repository.SourceTemplateRepository;
import org.srm.source.share.infra.constant.HitachiConstants;
import org.srm.source.share.infra.constant.ShareConstants;
import org.srm.web.annotation.Tenant;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * @author xiewenkui
 * @date 2021.09.07
 */
@Component
@Tenant(HitachiConstants.TENANT_NUM)
public class HitachiIRfxLineItemDomainServiceImpl extends IRfxLineItemDomainServiceImpl{

    private static final Logger LOOGGER = LoggerFactory.getLogger(HitachiIRfxLineItemDomainServiceImpl.class);

    @Autowired
    private SourceTemplateRepository sourceTemplateRepository;
    @Autowired
    private RfxLineItemRepository rfxLineItemRepository;
    @Autowired
    private QuotationDimensionRepository quotationDimensionRepository;
    @Autowired
    private QuotationDetailRepository quotationDetailRepository;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<RfxLineItem> insertOrUpdateLineItemList(RfxHeader rfxHeader, List<RfxLineItem> rfxLineItemList) {
        Long rfxHeaderId = rfxHeader.getRfxHeaderId();
        Long tenantId = rfxHeader.getTenantId();
        Long currentRoundNumber = rfxHeader.getRoundNumber();
        if (CollectionUtils.isEmpty(rfxLineItemList)) {
            return rfxLineItemList;
        }
        //?????????????????????
        try {
            RfxItemUpdateBeforeDTO rfxItemUpdateBeforeDTO = new RfxItemUpdateBeforeDTO();
            rfxItemUpdateBeforeDTO.setRfxHeader(rfxHeader);
            rfxItemUpdateBeforeDTO.setRfxLineItemList(rfxLineItemList);
            org.srm.common.client.entity.Tenant tenant = TenantInfoHelper.selectByTenantId(tenantId);
            LOOGGER.info("=== SSRC_RFX_ITEM_LINE_UPDATE_BEFORE Rfx AdaptorTask before Handle === {}", JSON.toJSON(rfxItemUpdateBeforeDTO));
            TaskResultBox taskResultBox = AdaptorTaskHelper.executeAdaptorTask(ShareConstants.AdaptorTask.SSRC_RFX_ITEM_LINE_UPDATE_BEFORE, tenant.getTenantNum(), rfxItemUpdateBeforeDTO);
            RfxItemUpdateBeforeDTO result = taskResultBox.get(0, RfxItemUpdateBeforeDTO.class);
            LOOGGER.info("=== SSRC_RFX_ITEM_LINE_UPDATE_BEFORE AdaptorTask Result === {}",JSON.toJSON(result));
            if (!result.getMsgCode().equals(ShareConstants.RESULT.SUCCESS)){
                throw new CommonException(result.getMsgCode());
            }
            rfxLineItemList = result.getRfxLineItemList();
            rfxHeader = result.getRfxHeader();
        }catch (TaskNotExistException e){
            //????????????
            LOOGGER.info("SSRC_RFX_ITEM_LINE_UPDATE_BEFORE AdaptorTask Not Found,tenantId:{}",tenantId);
        }

        List<RfxLineItem> addList = new ArrayList<>();
        List<RfxLineItem> updateList = new ArrayList<>();
        LongAdder longAdder = new LongAdder();
        SourceTemplate sourceTemplate = sourceTemplateRepository.selectByPrimaryKey(rfxHeader.getTemplateId());
        // ????????????????????????
        List<RfxLineItem> alreadyExistItems = rfxLineItemRepository.select(new RfxLineItem(tenantId, rfxHeaderId));
        Map<Long, RfxLineItem> rfxLineItemMap=new HashMap<>();
        if (CollectionUtils.isEmpty(alreadyExistItems)) {
            longAdder.add(BaseConstants.DEFAULT_TENANT_ID);
        } else {
            alreadyExistItems.stream().max(Comparator.comparing(RfxLineItem::getRfxLineItemNum)).ifPresent(rfxLineItem -> longAdder.add(rfxLineItem.getRfxLineItemNum()));
            rfxLineItemMap = alreadyExistItems.stream().collect(Collectors.toMap(RfxLineItem::getRfxLineItemId, a -> a, (k1, k2) -> k1));
            for(RfxLineItem item : rfxLineItemList) {
                RfxLineItem alreadyExist=  rfxLineItemMap.get(item.getRfxLineItemId());
                if(!Objects.isNull(alreadyExist)){
                    deleteQuoDetail(rfxHeaderId, tenantId, item, alreadyExist.getQuotationTemplateId());
                }
            }
        }

        //?????????????????????
        //?????????????????????????????????????????????????????????????????????
        Map<String, String> parameter = new HashMap<>(BaseConstants.Digital.ONE);
        parameter.put("company", rfxHeader.getCompanyId().toString());
        parameter.put("sourceCategory", rfxHeader.getSourceCategory());
        parameter.put("sourceTemplate", sourceTemplate.getTemplateNum());
        String benchmarkPriceType = CnfHelper.select(tenantId, ShareConstants.ConfigCenterCode.QUOTATION_SET, String.class).invokeWithParameter(parameter);

        for(RfxLineItem item : rfxLineItemList) {
            item.setCurrentRoundNumber(currentRoundNumber);
            //???????????????????????????
            if(null == item.getRfxLineItemId() || (CollectionUtils.isNotEmpty(alreadyExistItems) && null != rfxLineItemMap.get(item.getRfxLineItemId()) && null != rfxLineItemMap.get(item.getRfxLineItemId()).getQuotationTemplateId() && rfxLineItemMap.get(item.getRfxLineItemId()).getQuotationTemplateId().equals(item.getQuotationTemplateId())) || (CollectionUtils.isNotEmpty(alreadyExistItems) && null !=rfxLineItemMap.get(item.getRfxLineItemId()).getItemId() && !rfxLineItemMap.get(item.getRfxLineItemId()).getItemId().equals(item.getItemId())) || null==item.getQuotationTemplateId()){
                if(null != item.getRfxLineItemId() && CollectionUtils.isNotEmpty(alreadyExistItems) && null !=rfxLineItemMap.get(item.getRfxLineItemId()) && null !=rfxLineItemMap.get(item.getRfxLineItemId()).getItemId() && !rfxLineItemMap.get(item.getRfxLineItemId()).getItemId().equals(item.getItemId())){
                    item.setQuotationTemplateId(null);
                }
                if(null == item.getQuotationTemplateId() || CollectionUtils.isNotEmpty(alreadyExistItems) && null !=rfxLineItemMap.get(item.getRfxLineItemId()) && null !=rfxLineItemMap.get(item.getRfxLineItemId()).getItemId() && !rfxLineItemMap.get(item.getRfxLineItemId()).getItemId().equals(item.getItemId())){
                    QuotationDimension quotationDimension = new QuotationDimension(tenantId, item.getItemCategoryId(), item.getItemId());
                    quotationDimension.setDeleteFlag(BaseConstants.Flag.YES);
                    QuotationTemplate quotationTemplate = quotationDimensionRepository.selectLatestQuotationTemplate(quotationDimension);
                    if (quotationTemplate == null && null !=item.getItemId() && null !=item.getItemCategoryId()) {
                        quotationDimension.setQuotationDimension("ITEM_CATEGORY");
                        quotationDimension.setQuotationDimensionValue(item.getItemCategoryId());
                        quotationTemplate = quotationDimensionRepository.selectLatestQuotationTemplate(quotationDimension);
                    }
                    if(null !=quotationTemplate){
                        deleteQuoDetail(rfxHeaderId, tenantId, item, quotationTemplate.getTemplateId());
                        if(Objects.isNull(item.getRfxLineItemId())||Objects.nonNull(item.getQuotationTemplateId())){
                            item.setQuotationTemplateId(quotationTemplate.getTemplateId());
                        }
                    }}
            }

            //?????????????????????????????????????????????????????????????????????????????????
            if (Objects.nonNull(item.getEstimatedPrice()) || Objects.nonNull(item.getNetEstimatedPrice())){

                if(SourceConstants.BenchmarkPriceType.TAX_INCLUDED_PRICE.equals(benchmarkPriceType)){
                    if (BaseConstants.Digital.ZERO == item.getTaxIncludedFlag() || Objects.isNull(item.getTaxRate())) {
                        item.setNetEstimatedPrice(item.getEstimatedPrice());
                    } else {
                        //?????????????????????
                        BigDecimal netEstimatedPrice = Objects.isNull(item.getEstimatedPrice()) ? null : item.getEstimatedPrice().divide(BigDecimal.ONE.add(item.getTaxRate().divide(new BigDecimal(100))), 10, BigDecimal.ROUND_HALF_UP);
                        item.setNetEstimatedPrice(netEstimatedPrice);
                    }
                } else {
                    if (BaseConstants.Digital.ZERO == item.getTaxIncludedFlag() || Objects.isNull(item.getTaxRate())){
                        item.setEstimatedPrice(item.getNetEstimatedPrice());
                    } else {
                        //?????????????????????
                        BigDecimal estimatedPrice = Objects.isNull(item.getNetEstimatedPrice()) ? null : item.getNetEstimatedPrice().multiply(BigDecimal.ONE.add(item.getTaxRate().divide(new BigDecimal(100))));
                        item.setEstimatedPrice(estimatedPrice);
                    }
                }

                //?????????????????????
                item.setEstimatedAmount(Objects.isNull(item.getEstimatedPrice()) ? null : item.getRfxQuantity().multiply(item.getEstimatedPrice()));

                item.setNetEstimatedAmount(Objects.isNull(item.getNetEstimatedPrice()) ? null : item.getNetEstimatedPrice());

            }


            item.setSelectionStrategy(sourceTemplate.getSelectionStrategy());
            if (item.getRfxLineItemId() == null) {
                longAdder.increment();
                item.setRfxLineItemNum(longAdder.longValue());
                item.setRfxHeaderId(rfxHeaderId);
                item.setTenantId(tenantId);
                //??????????????????????????????????????????????????????????????????????????????
                addList.add(item);
            } else {
                updateList.add(item);
                //??????????????????(??????????????????????????????,?????????????????????)
                item.setSourceFrom(ShareConstants.SourceCategory.RFX);
            }
        }
        if (CollectionUtils.isNotEmpty(addList)) {
            this.rfxLineItemRepository.batchInsertSelective(addList);
        }
        if (CollectionUtils.isNotEmpty(updateList)) {
            this.handlePrLineItems(rfxHeader,updateList);
            this.rfxLineItemRepository.batchUpdateItemOptional(updateList);
            //???????????????????????????????????????????????????,?????????????????????????????????????????????
            this.updateItemSupplierAssign(updateList,tenantId,rfxHeaderId);
        }
        return addList;
    }

    private void deleteQuoDetail(Long rfxHeaderId, Long tenantId, RfxLineItem item, Long templateId) {
        if (!Objects.equals(item.getQuotationTemplateId(), templateId)) {
            //?????????????????????????????????????????????????????????
            List<QuotationDetail> quotationDetailSubList = quotationDetailRepository.selectByCondition(Condition.builder(QuotationDetail.class)
                    .andWhere(Sqls.custom()
                            .andEqualTo(QuotationDetail.FIELD_TENANT_ID, tenantId)
                            .andEqualTo(QuotationDetail.FIELD_SOURCE_FROM, ShareConstants.SourceCategory.RFX)
                            .andEqualTo(QuotationDetail.FIELD_RFX_LINE_ITEM_ID, item.getRfxLineItemId())
                            .andEqualTo(QuotationDetail.FIELD_SOURCE_HEADER_ID, rfxHeaderId))
                    .build());
            if (CollectionUtils.isNotEmpty(quotationDetailSubList)) {
                quotationDetailRepository.batchDeleteByPrimaryKey(quotationDetailSubList);
            }
        }
    }
}
