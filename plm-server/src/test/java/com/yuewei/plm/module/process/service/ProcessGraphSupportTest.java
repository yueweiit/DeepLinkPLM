package com.yuewei.plm.module.process.service;
import com.yuewei.plm.module.process.dto.*;
import com.yuewei.plm.common.exception.BusinessException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProcessGraphSupportTest {
    private ProcessOperationDTO node(String code,int order){var n=new ProcessOperationDTO();n.setNodeCode(code);n.setOperationCode("PROC-ASSEMBLY");n.setProcessName("明确工序");n.setSequenceNo(order);return n;}
    private ProcessRouteSaveDTO.Edge edge(String from,String to){return new ProcessRouteSaveDTO.Edge(from,to,10,"finish_to_start",null,null);}
    @Test void preservesBranchAndMergeWithoutRequiringLegacyManufacturingCodes(){assertDoesNotThrow(()->ProcessGraphSupport.validate(List.of(node("A",10),node("B",20),node("C",30),node("D",40)),List.of(edge("A","B"),edge("A","C"),edge("B","D"),edge("C","D"))));}
    @Test void unknownEndpointOrCycleIsRejected(){var nodes=List.of(node("A",10),node("B",20));assertThrows(BusinessException.class,()->ProcessGraphSupport.validate(nodes,List.of(edge("A","missing"))));assertThrows(BusinessException.class,()->ProcessGraphSupport.validate(nodes,List.of(edge("A","B"),edge("B","A"))));}
    @Test void duplicateSelfEdgeOrMissingGraphPayloadCannotEraseStructure(){var nodes=List.of(node("A",10),node("B",20));assertThrows(BusinessException.class,()->ProcessGraphSupport.validate(nodes,List.of(edge("A","A"))));assertThrows(BusinessException.class,()->ProcessGraphSupport.validate(nodes,List.of(edge("A","B"),edge("A","B"))));assertThrows(BusinessException.class,()->ProcessGraphSupport.validate(nodes,null));}
    @Test void stableNodeIdentityAndOrderMustBeUnique(){assertThrows(BusinessException.class,()->ProcessGraphSupport.validate(List.of(node("A",10),node("A",20)),List.of()));assertThrows(BusinessException.class,()->ProcessGraphSupport.validate(List.of(node("A",10),node("B",10)),List.of()));}
}
