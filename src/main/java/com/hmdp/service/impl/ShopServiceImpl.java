package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryShopById(Long id) {
        //  缓存穿透
        //  Shop shop = queryWithPassThrough(id);
        Shop shop = queryWithMutex(id);
        if(null == shop){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);

    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        //更新数据库 删除缓存
        updateById(shop);
        Long id = shop.getId();
        if(null == id){
            return Result.fail("店铺id不能为空");
        }
        String key = CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public Shop queryWithPassThrough(Long id){
        //从redis中查询出商户缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(null != shopJson ){
            //返回错误信息
            return null;
        }

        Shop shop = getById(id);
        if(null == shop){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //存在 写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    public Shop queryWithMutex(Long id) {
        //从redis中查询出商户缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        while(StrUtil.isNotBlank(shopJson)){
            RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
            Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
            return shop;
        }
        if(null != shopJson ){
            //返回错误信息 查到了缓存穿透的null值
            return null;
        }

        //未命中 查数据库
        //实现缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(LOCK_SHOP_KEY);
            //判断是否获取成功
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //失败，则休眠并重试


            shop = getById(id);
            Thread.sleep(200); // 模拟重建延迟
            if(null == shop){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //存在 写入redis
            RedisData redisData = new RedisData();
            redisData.setData(shop);
            redisData.setExpireTime(LocalDateTime.now().plusSeconds(30));
            //
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException();
        }finally {
            unlock(lockKey);
        }

        return shop;
    }

    public Shop queryWithLogicalExpire(Long id) {
        //从redis中查询出商户缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
           return null;
        }
        // 命中 需要把json反序列化为对象
        // 判断是否过期
        // 未过期 直接返回店铺信息
        // 已过期 需要缓存重建
        // 获取互斥锁
        // 判断是否获取锁成功 失败 返回店铺信息
        // 成功 开启新线程 实现缓存重建
        RedisData redisData = JSONUtil.toBean(shopJson,RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            return  shop;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //
            CACHE_REBUILD_EXECUTOR.submit(()->{
                //重建缓存
                try {
                    this.savaShopToRedis(id,30L);
                } catch (Exception e) {
                    e.printStackTrace();
                }finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }
        //未命中 查数据库
        //实现缓存重建
        //获取互斥锁


        return shop;
    }

    public void savaShopToRedis(Long id,Long expireSeconds){
        //查询店铺数据
        Shop shop = getById(id);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
}
