package com.yuewei.plm.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuewei.plm.repository.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProductRepository extends BaseMapper<Product> {
    @Select("select * from plm_product where product_id=#{id} and deleted_flag=0 for update")
    Product selectIdentityForUpdate(@Param("id") Long id);
}
