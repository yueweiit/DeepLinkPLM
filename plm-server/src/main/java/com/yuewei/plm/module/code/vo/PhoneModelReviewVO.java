package com.yuewei.plm.module.code.vo;

import java.util.List;

public record PhoneModelReviewVO(String key, List<String> codeValues, List<String> modelNames, long sourceCount,
    String sourceDigest,String reviewState,String conclusion,String reviewReason,String reviewedAt,String reviewedBy,
    List<Reference> references,List<Long> reviewPhoneModelIds,boolean canReview,String reviewBlockReason) {
    public record Reference(long projectId,String productType,String productName,String model,String phoneModelCode,Long phoneModelId,String currentPhoneName) {}
    public PhoneModelReviewVO(String key,List<String> codeValues,List<String> modelNames,long sourceCount) {
        this(key,codeValues,modelNames,sourceCount,null,"pending",null,null,null,null,List.of(),List.of(),false,null);
    }
}
