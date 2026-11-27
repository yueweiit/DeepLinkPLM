package com.yuewei.plm.module.moldsharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinishedProductMappingServiceTest {
    private static final List<Long> PHONES=List.of(101L,102L,103L);
    private final ProductBusinessCodeGenerator codes=new ProductBusinessCodeGenerator();

    private FinishedProductMappingService.MoldBasis basis(long phone,String material,String alias,String primary,long id){
        return new FinishedProductMappingService.MoldBasis(id,100+id,8,44,phone,"Model "+phone,
            phone==101?"1121":phone==102?"1122":"1123",material,alias,primary,"","Shared mold",1);
    }

    private List<FinishedProductMappingService.MoldBasis> completeSet(){
        return List.of(
            basis(101,"10","MBA10J1121","MBA10J1121",0),basis(101,"20","MBA20J1121","MBA20J1121",0),
            basis(102,"10","MBA10J1122","MBA10J1121",12),basis(102,"20","MBA20J1122","MBA20J1121",13),
            basis(103,"10","MBA10J1123","MBA10J1121",14),basis(103,"20","MBA20J1123","MBA20J1121",15));
    }

    @Test void groupsBaseAndSeveralTargetsOnlyWhenEveryModelHasBothMoldMaterials(){
        var grouped=FinishedProductMappingService.groupMoldSets(completeSet(),PHONES,"precision","BA","1121",codes);
        assertEquals(1,grouped.size());
        assertEquals(6,grouped.get(0).bases().size());
        assertEquals("MS-000044",grouped.get(0).physicalCode());
    }

    @Test void incompleteMaterialOrMismatchedHoleCannotBecomeAProductMappingBasis(){
        var incomplete=new ArrayList<>(completeSet());incomplete.removeIf(row->row.phoneModelId()==103&&row.materialCode().equals("20"));
        assertTrue(FinishedProductMappingService.groupMoldSets(incomplete,PHONES,"precision","BA","1121",codes).isEmpty());
        var wrongHole=new ArrayList<>(completeSet());var row=wrongHole.get(4);wrongHole.set(4,basis(103,"10","MBA10D1123","MBA10J1121",14));
        assertTrue(FinishedProductMappingService.groupMoldSets(wrongHole,PHONES,"precision","BA","1121",codes).isEmpty());
    }

    @Test void rejectsMoldAliasesWhoseProductOrPhoneSegmentsDoNotMatchTheRelationship(){
        var wrongAlias=new ArrayList<>(completeSet());wrongAlias.set(4,basis(103,"10","MBA10J1122","MBA10J1121",14));
        assertTrue(FinishedProductMappingService.groupMoldSets(wrongAlias,PHONES,"precision","BA","1121",codes).isEmpty());
        var wrongProduct=new ArrayList<>(completeSet());wrongProduct.set(4,basis(103,"10","MFA10J1123","MBA10J1121",14));
        assertTrue(FinishedProductMappingService.groupMoldSets(wrongProduct,PHONES,"precision","BA","1121",codes).isEmpty());
    }

    @Test void sharedMappingsMustStayAnchoredToTheOriginalPrimaryMoldPhone(){
        ExistingMoldGroupMappingService.requireOriginalReference("MBA10J1121","1121");
        assertThrows(com.yuewei.plm.common.exception.BusinessException.class,
            ()->ExistingMoldGroupMappingService.requireOriginalReference("MBA10J1121","1122"));
    }
}
