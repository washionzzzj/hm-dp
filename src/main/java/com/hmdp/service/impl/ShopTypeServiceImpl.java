package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public Result queryTypeList() {
        List<ShopType> typeList = new ArrayList<>();
        String keys = "cache:shoptype:";
        Set<String> cachekeys = stringRedisTemplate.keys(keys+"*");
        if(!cachekeys.isEmpty()){
            List<String> shoptypes = stringRedisTemplate.opsForValue().multiGet(cachekeys);

            for(String shopTypeJSON:shoptypes){
                ShopType shopType = JSONUtil.toBean(shopTypeJSON, ShopType.class);
                typeList.add(shopType);
            }
//            JSONUtil.to
            return Result.ok(typeList);
        }
        typeList = query().orderByAsc("sort").list();
        for(ShopType shopType : typeList){
            stringRedisTemplate.opsForValue().set(keys+shopType.getId(),JSONUtil.toJsonStr(shopType),
                    CACHE_SHOP_TTL, TimeUnit.MINUTES);

        }
        return Result.ok(typeList);
    }
}
