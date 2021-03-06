package org.srm.source.rfx.app.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import io.choerodon.core.exception.CommonException;
import io.choerodon.core.oauth.DetailsHelper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.servicecomb.pack.omega.context.annotations.SagaStart;
import org.hzero.boot.platform.code.builder.CodeRuleBuilder;
import org.hzero.core.base.AopProxy;
import org.hzero.core.base.BaseConstants;
import org.hzero.core.helper.LanguageHelper;
import org.hzero.core.redis.RedisHelper;
import org.hzero.core.util.Regexs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.srm.boot.adaptor.client.AdaptorTaskHelper;
import org.srm.boot.adaptor.client.exception.TaskNotExistException;
import org.srm.boot.adaptor.client.result.TaskResultBox;
import org.srm.common.TenantInfoHelper;
import org.srm.source.bid.domain.repository.SourceNoticeRepository;
import org.srm.source.bid.domain.service.ISourceNoticeDomainService;
import org.srm.source.rfx.api.dto.CheckPriceDTO;
import org.srm.source.rfx.api.dto.CheckPriceHeaderDTO;
import org.srm.source.rfx.api.dto.HeaderQueryDTO;
import org.srm.source.rfx.api.dto.RfxCheckItemDTO;
import org.srm.source.rfx.app.service.RfxHeaderService;
import org.srm.source.rfx.app.service.RfxLineItemService;
import org.srm.source.rfx.app.service.RfxMemberService;
import org.srm.source.rfx.app.service.SourceMatterConfService;
import org.srm.source.rfx.domain.entity.*;
import org.srm.source.rfx.domain.repository.CommonQueryRepository;
import org.srm.source.rfx.domain.repository.RfxHeaderRepository;
import org.srm.source.rfx.domain.repository.RfxLineItemRepository;
import org.srm.source.rfx.domain.service.IRfxActionDomainService;
import org.srm.source.rfx.domain.service.IRfxHeaderDomainService;
import org.srm.source.rfx.domain.service.IRfxLineItemDomainService;
import org.srm.source.rfx.domain.service.v2.RfxHeaderDomainService;
import org.srm.source.rfx.domain.vo.RfxFullHeader;
import org.srm.source.rfx.infra.constant.SourceConstants;
import org.srm.source.rfx.infra.constant.SourceConstants.ActionOperation;
import org.srm.source.rfx.infra.mapper.RfxQuotationLineMapper;
import org.srm.source.rfx.infra.util.CopyUtils;
import org.srm.source.rfx.infra.util.RfxEventUtil;
import org.srm.source.share.app.service.MessageSendService;
import org.srm.source.share.app.service.RoundHeaderService;
import org.srm.source.share.app.service.SourceTemplateService;
import org.srm.source.share.domain.entity.PrequalHeader;
import org.srm.source.share.domain.entity.SourceTemplate;
import org.srm.source.share.domain.entity.User;
import org.srm.source.share.domain.repository.PrequalHeaderRepository;
import org.srm.source.share.domain.repository.SourceTemplateRepository;
import org.srm.source.share.domain.service.IEvaluateDomainService;
import org.srm.source.share.domain.service.IPrequelDomainService;
import org.srm.source.share.infra.constant.HitachiConstants;
import org.srm.source.share.infra.constant.ShareConstants;
import org.srm.web.annotation.Tenant;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * ???????????????????????????????????????
 *
 * @author xuan.zhang03@hand-china.com 2018-12-27 18:44:58
 */
@Service
@Tenant(HitachiConstants.TENANT_NUM)
public class HitachiRfxHeaderServiceImpl extends RfxHeaderServiceImpl implements RfxHeaderService, AopProxy<RfxHeaderService> {

	@Autowired
	private RfxHeaderRepository rfxHeaderRepository;
	@Autowired
	private RfxLineItemRepository rfxLineItemRepository;
	@Autowired
	private IRfxActionDomainService actionDomainService;
	@Autowired
	private IRfxActionDomainService rfxActionDomainService;
	@Autowired
	private IRfxHeaderDomainService rfxHeaderDomainService;
	@Autowired
	private SourceTemplateRepository sourceTemplateRepository;
	@Autowired
	private IRfxLineItemDomainService rfxLineItemDomainService;
	@Autowired
	private CommonQueryRepository commonQueryRepository;
	@Autowired
	private PrequalHeaderRepository prequalHeaderRepository;
	@Autowired
	@Lazy
	private RoundHeaderService roundHeaderService;
	@Autowired
	private RfxEventUtil rfxEventUtil;
	@Autowired
	@Lazy
	private ISourceNoticeDomainService sourceNoticeDomainService;
	@Autowired
	private SourceNoticeRepository sourceNoticeRepository;
	@Autowired
	private MessageSendService messageSendService;
	@Autowired
	private RfxHeaderDomainService rfxHeaderDomainServiceV2;
	@Autowired
	private RfxLineItemService rfxLineItemService;
	@Autowired
	private RfxQuotationLineMapper rfxQuotationLineMapper;
	@Autowired
	private RedisHelper redisHelper;
	@Autowired
	@Lazy
	private IPrequelDomainService prequelDomainService;
	@Autowired
	private IEvaluateDomainService evaluateDomainService;
	@Autowired
	private SourceMatterConfService sourceMatterConfService;
	@Autowired
	private SourceTemplateService sourceTemplateService;
	@Autowired
	private CodeRuleBuilder codeRuleBuilder;
	@Autowired
	@Lazy
	private RfxMemberService rfxMemberService;

	private static final Logger LOGGER = LoggerFactory.getLogger(HitachiRfxHeaderServiceImpl.class);
	@Override
	@Transactional(rollbackFor = Exception.class)
	public RfxHeader rfxApproval(Long tenantId, Long rfxHeaderId, Integer selfFlag) {
		RfxHeader rfxHeader = rfxHeaderRepository.selectByPrimaryKey(rfxHeaderId);
		//??????????????????????????????????????????????????????????????????????????????????????????
		if(!SourceConstants.RfxStatus.RELEASE_APPROVING.equals(rfxHeader.getRfxStatus())
				&& BaseConstants.Digital.ZERO == selfFlag){
			LOGGER.info("===============Release rfx status error==============={}",rfxHeader.getRfxStatus());
			return rfxHeader;
		}
		SourceTemplate sourceTemplate = sourceTemplateRepository.selectByPrimaryKey(rfxHeader.getTemplateId());
		rfxHeader.setRfxStatus(SourceConstants.RfxStatus.IN_QUOTATION);
		Assert.notNull(rfxHeader, SourceConstants.ErrorCode.NO_RFX_HEADER_ID);
		List<RfxLineItem> itemList = rfxLineItemRepository.select(new RfxLineItem(tenantId, rfxHeaderId));
		rfxHeaderDomainServiceV2.initHeaderDateByAutoRoundQuotation(rfxHeader, sourceTemplate);
		rfxHeaderDomainService.initItemStartAndEndDate(rfxHeader, itemList);
		rfxHeader.setBargainRule(sourceTemplate.getBargainRule());
		rfxHeader.initAfterApproval();
		//????????????????????????????????????
		if (ShareConstants.SourceTemplate.ReleaseApproveType.WFL.equals(sourceTemplate.getReleaseApproveType()) || ShareConstants.SourceTemplate.ReleaseApproveType.WFL_ALLOW.equals(sourceTemplate.getReleaseApproveType())) {
			rfxHeader.setAttributeBigint1(DetailsHelper.getUserDetails().getUserId());
		}
		rfxHeaderRepository.updateByPrimaryKeySelective(rfxHeader);
		// ?????????????????????
		roundHeaderService.initRoundHeader(rfxHeader, sourceTemplate);
		// ??????????????????????????????????????????
		rfxLineItemDomainService.initQuotationDetail(rfxHeader, itemList);
		// ??????????????????
		sourceNoticeDomainService.processRfxNotice(rfxHeader, sourceNoticeRepository.querySourceNotice(rfxHeader.getTenantId(), rfxHeader.getSourceFrom(), "BR", rfxHeader.getRfxHeaderId()));
		//??????????????????????????????????????????
		if (BaseConstants.Digital.ZERO == selfFlag) {
			rfxActionDomainService.insertAction(rfxHeader, SourceConstants.RfxActionOperation.APPROVE,
					ActionOperation.ZH.equals(LanguageHelper.language()) ? ActionOperation.CH.RFX_RELEASE_APPROVE : ActionOperation.ENG.RFX_RELEASE_APPROVE);
		}
		actionDomainService.insertActionForFlow(new RfxAction(rfxHeader.getRfxHeaderId(), rfxHeader.getTenantId(), rfxHeader.getReleasedBy(), rfxHeader.getRfxStatus(),
				SourceConstants.RfxActionOperation.ISSUE, new Date(), null));
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("rfxHeader:{}", rfxHeader.toJSONString());
		}
		// ?????????????????????????????????
		if (!SourceConstants.EntryMethod.OFFLINE.equals(rfxHeader.getQuotationType())) {
			String serverName = commonQueryRepository.selectServerName(tenantId);
			rfxHeader.setServerName(serverName);
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("?????????????????????????????? serverName:{}", serverName);
			}
			messageSendService.sendRfxReleaseMessage(rfxHeader);
		}
		if (SourceConstants.SourceQualificationType.NONE.equals(sourceTemplate.getQualificationType())
				|| SourceConstants.SourceQualificationType.POST.equals(sourceTemplate.getQualificationType())) {
			prequalHeaderRepository.delete(new PrequalHeader(rfxHeader.getRfxHeaderId(), ShareConstants.SourceTemplate.CategoryType.RFX));
		}
		//????????????????????????
		initExpenses(sourceTemplate,rfxHeader);
		//-----????????????????????????-----
		//?????????????????????????????????+1
		rfxHeader.setQualificationType(sourceTemplate.getQualificationType());
		rfxEventUtil.eventSend(RfxEventUtil.EventCode.SSRC_RFX_RELEASE_APPROVE, RfxEventUtil.Action.RELEASE_APPROVE, rfxHeader);
		return rfxHeader;
	}

	@Override
	public CheckPriceHeaderDTO latestValidCreateItemCheck(Long organizationId, Long rfxHeaderId, CheckPriceHeaderDTO checkPriceHeaderDTO) {
		//???????????????????????????????????????????????????????????????
		if(CollectionUtils.isNotEmpty(checkPriceHeaderDTO.getCheckPriceDTOLineList().get(0).getQuotationLineList())){
			if(BaseConstants.Flag.YES.equals(checkPriceHeaderDTO.getCheckPriceDTOLineList().get(0).getQuotationLineList().get(0).getAttributeTinyint10())){
				checkPriceHeaderDTO.setCreateItemFlag(BaseConstants.Flag.NO);
				return checkPriceHeaderDTO;
			}
		}else{
			HeaderQueryDTO headerQueryDTO = new HeaderQueryDTO(checkPriceHeaderDTO.getRfxHeaderId(), organizationId);
			List<RfxCheckItemDTO> rfxCheckItems = rfxQuotationLineMapper.selectQuotationDetail(headerQueryDTO).stream().filter(e -> (e.getAttributeTinyint10() != null)).collect(Collectors.toList());
			if(CollectionUtils.isNotEmpty(rfxCheckItems) && BaseConstants.Flag.YES.equals(rfxCheckItems.get(0).getAttributeTinyint10())){
				checkPriceHeaderDTO.setCreateItemFlag(BaseConstants.Flag.NO);
				return checkPriceHeaderDTO;
			}
		}

		List<Long> rfxLineItemId = null;
		//????????????Tab??????ITEM?????????????????????????????????
		if (CollectionUtils.isNotEmpty(checkPriceHeaderDTO.getCheckPriceDTOLineList())
				&& CheckPriceDTO.ITEM.equals(checkPriceHeaderDTO.getCheckPriceDTOLineList().get(BaseConstants.Digital.ZERO).getType())) {
			rfxLineItemId = checkPriceHeaderDTO.getCheckPriceDTOLineList().stream().filter(checkPriceDTO
					-> !SourceConstants.SelectionStrategy.RELEASE.equals(checkPriceDTO.getSelectionStrategy()))
					.map(CheckPriceDTO::getRfxLineItemId).collect(Collectors.toList());
			if (CollectionUtils.isEmpty(rfxLineItemId)) {
				checkPriceHeaderDTO.setCreateItemFlag(BaseConstants.Flag.NO);
				return checkPriceHeaderDTO;
			}
		}


		//?????????????????????????????????????????????
		String itemGeneratePolicy = commonQueryRepository.queryCustomizeSettingValueByCode(organizationId, ShareConstants.CustomizeSettingCode.CREATE_ITEM, BaseConstants.Digital.ZERO);
		//???????????????
		int isCreateItem = StringUtils.isEmpty(itemGeneratePolicy) ? BaseConstants.Digital.ZERO : Integer.parseInt(itemGeneratePolicy);
		Long itemCodelessCount = rfxLineItemService.queryLineItemCodelessCount(organizationId, rfxHeaderId, rfxLineItemId);
		//0???false,1/2???true,1?????????,2?????????
		checkPriceHeaderDTO.setCreateItemFlag(itemCodelessCount > 0 ? isCreateItem : BaseConstants.Digital.ZERO);
		return checkPriceHeaderDTO;
	}

	@Override
	@SagaStart
	@Transactional(rollbackFor = Exception.class)
	public RfxFullHeader saveOrUpdateFullHeader(RfxFullHeader rfxFullHeader) {
		// ??????????????????
		RfxHeader header = rfxFullHeader.getRfxHeader();
		Assert.notNull(header.getRfxHeaderId(), SourceConstants.ErrorCode.NO_RFX_HEADER_ID);

		PrequalHeader prequalHeader = rfxFullHeader.getPrequalHeader();
		List<RfxLineItem> itemList = rfxFullHeader.getRfxLineItemList();
		List<RfxLineSupplier> supplierList = rfxFullHeader.getRfxLineSupplierList();
		SourceTemplate sourceTemplate = sourceTemplateRepository.selectByPrimaryKey(header.getTemplateId());
		redisHelper.strSet("ssrc:rfx:source:template:" + header.getTemplateId(), redisHelper.toJson(sourceTemplate), 3, TimeUnit.MINUTES);
		// ????????????????????????
		rfxHeaderDomainService.validRfxHeaderBeforeSave(header, sourceTemplate);
		// ??????????????????
		this.self().changeTemplate(rfxFullHeader);
		// ??????????????????
		prequelDomainService.updatePrequelHeader(sourceTemplate, Objects.isNull(header.getQuotationStartDate()) ? header.getEstimatedStartTime() : header.getQuotationStartDate(), prequalHeader);
		// ????????????????????????
		rfxHeaderDomainService.processRfxLineItem(header, itemList);

		// ??????????????????
		sourceNoticeDomainService.processRfxNotice(header, rfxFullHeader.getSourceNotice());

		// ???????????????????????????
		rfxHeaderDomainService.processRfxLineSupplier(header, supplierList);
		//????????????????????????????????????????????????
		rfxLineItemService.checkRfxSuppliers(header.getTenantId(), header.getRfxHeaderId());

		//????????????????????????
		evaluateDomainService.createOrUpdateEvaluateExpert(rfxFullHeader.getRfxHeader().getTenantId(), rfxFullHeader.getEvaluateExperts(), ShareConstants.Expert.EvaluateStatus.SUBMITTED);

		//??????????????????
		evaluateDomainService.createOrUpdateEvaluateEvaluateIndic(rfxFullHeader.getRfxHeader().getTenantId(), rfxFullHeader.getEvaluateIndics(), ShareConstants.Expert.EvaluateStatus.SUBMITTED);

		//????????????????????????????????????
		this.initBudget(header);
		// ??????????????????????????????
		rfxHeaderDomainServiceV2.processRoundQuotationDate(sourceTemplate, header, rfxFullHeader.getRoundHeaderDates());
		// ?????????????????????
		rfxHeaderDomainServiceV2.processMultiSections(rfxFullHeader.getProjectLineSections());
		this.rfxHeaderRepository.updateRfxHeader(header);
		rfxFullHeader.setRfxHeader(header);
		//??????????????????
		redisHelper.delKey("ssrc:rfx:source:template:" + header.getTemplateId());
		return rfxFullHeader;
	}

	@Override
	public RfxHeader createOrUpdateSimpleRfxHeader(RfxHeader rfxHeader) {
		if (rfxHeader.getRfxHeaderId() == null || rfxHeader.getRfxHeaderId().equals(0L)) {
			// ??????????????????
			SourceTemplate sourceTemplate = sourceTemplateService.selectByPrimaryKey(rfxHeader.getTemplateId());
			Assert.notNull(sourceTemplate, ShareConstants.ErrorCode.ERROR_SOURCE_TEMPLATE_ID_NOT_FOUND);
			rfxHeader.setRfxNum(codeRuleBuilder.generateCode(DetailsHelper.getUserDetails().getTenantId(), SourceConstants.CodeRule.RFX_NUM, "GLOBAL", "GLOBAL", null));
			rfxHeader.initAuctionRule(sourceTemplate);
			rfxHeader.initPretrialUser(sourceTemplate);
			rfxHeader.initPreQualificationFlag(sourceTemplate);
			rfxHeader.setQuotationScope(StringUtils.isBlank(rfxHeader.getQuotationScope()) ? sourceTemplate.getQuotationScope() : rfxHeader.getQuotationScope());
			rfxHeader.setMultiCurrencyFlag(sourceTemplate.getMultiCurrencyFlag());
			rfxHeader.setPaymentTermFlag(sourceTemplate.getPaymentTermFlag());
			rfxHeader.setSealedQuotationFlag(rfxHeader.getSealedQuotationFlag() == null ? sourceTemplate.getSealedQuotationFlag() : rfxHeader.getSealedQuotationFlag());
			rfxHeader.setCheckedBy(DetailsHelper.getUserDetails().getUserId());
			rfxHeader.setMinQuotedSupplier(Objects.isNull(rfxHeader.getMinQuotedSupplier()) ? sourceTemplate.getMinQuotedSupplier() : rfxHeader.getMinQuotedSupplier());
			rfxHeader.setLackQuotedSendFlag(BaseConstants.Flag.NO);
			rfxHeader.setOnlyAllowAllWinBids(rfxHeader.getOnlyAllowAllWinBids() == null ? sourceTemplate.getOnlyAllowAllWinBids() : rfxHeader.getOnlyAllowAllWinBids());
			//???????????????????????? ????????????????????????????????????
			rfxHeader.validationCategoryType();
			if (StringUtils.isBlank(rfxHeader.getPurName())) {
				rfxHeader.setPurName(DetailsHelper.getUserDetails().getRealName());
			}
			if (StringUtils.isBlank(rfxHeader.getPurEmail())) {
				rfxHeader.setPurEmail(DetailsHelper.getUserDetails().getEmail());
			}
			if (StringUtils.isBlank(rfxHeader.getPurPhone())) {
				User user = commonQueryRepository.getUserInfoById(DetailsHelper.getUserDetails().getUserId());
				rfxHeader.setPurPhone(user.getPhone());
				rfxHeader.setInternationalTelCode(user.getInternationalTelCode());
			}

			rfxHeader.setQuotationRounds(sourceTemplate.getQuotationRounds());
			if (rfxHeader.getBidBond() == null) {
				rfxHeader.setBidBond(BigDecimal.ZERO);
			}
//			rfxHeader.setMatterDetail(sourceTemplate.getMatterDetail());
			// ??????????????????????????????
			SourceMatterConf sourceMatterConf = sourceMatterConfService.getMatterRequireAndDetail(rfxHeader.getTenantId(), rfxHeader.getCompanyId());
			if (sourceMatterConf != null) {
				rfxHeader.setMatterRequireFlag(sourceMatterConf.getRfxRequireFlag());
				if (rfxHeader.getMatterRequireFlag() == 1) {
					//??????????????????????????????????????????????????????
					rfxHeader.setMatterDetail(sourceTemplate.getMatterDetail());
					if (rfxHeader.getMatterDetail() != null) {
						rfxHeader.setMatterDetail(rfxHeader.getMatterDetail());
					}
				}
			}

			//??????????????????
			org.srm.common.client.entity.Tenant tenant = TenantInfoHelper.selectByTenantId(rfxHeader.getTenantId());
			//???????????????????????????
			try {
				TaskResultBox taskResultBox = AdaptorTaskHelper.executeAdaptorTask(ShareConstants.AdaptorTask.SSRC_TMPL_TO_SOURCE, tenant.getTenantNum(), sourceTemplate);
				RfxHeader rfxHeaderCopy = taskResultBox.get(0, RfxHeader.class);
				BeanUtils.copyProperties(rfxHeaderCopy, rfxHeader, CopyUtils.getNullPropertyNames(rfxHeaderCopy));
			} catch (TaskNotExistException e) {
//				LOGGER.info("============SSRC_TMPL_TO_SOURCE-TaskNotExistException=============={}",tenant.getTenantNum(),e.getMessage(),e.getStackTrace());
			}
			this.rfxHeaderRepository.insertSelective(rfxHeader);
			//????????????????????????????????????????????????????????????
			if (rfxHeader.getCopyRfxHeaderId() == null) {
				//????????????
				rfxHeader.setSourceFrom(StringUtils.isBlank(rfxHeader.getSourceFrom()) ? SourceConstants.SourceFrom.MANUAL : rfxHeader.getSourceFrom());
				//?????????????????????
				//???????????? ????????????????????????
				rfxHeader.setPasswordFlag(sourceTemplate.getPasswordFlag());
				rfxMemberService.initRfxMember(rfxHeader);
			} else {
				rfxHeader.setSourceFrom(StringUtils.isBlank(rfxHeader.getSourceFrom()) ? SourceConstants.SourceFrom.COPY : rfxHeader.getSourceFrom());
			}
			// ??????????????????
			this.rfxActionDomainService.insertAction(rfxHeader, SourceConstants.RfxActionOperation.CREATE, null);
			//---?????????????????????--
			//1.?????????+1
			rfxEventUtil.eventSend(RfxEventUtil.EventCode.SSRC_RFX_CREATE, RfxEventUtil.Action.CREATE, rfxHeader);
		} else {
			// ??????????????????
			this.rfxHeaderRepository.updateRfxHeader(rfxHeader);
		}
		return rfxHeader;
	}

}
