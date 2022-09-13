package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private SeckillVoucherServiceImpl seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        // TODO 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if(null == voucher) return Result.fail("优惠券不存在!");
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //尚未开始
            return Result.fail("秒杀尚未开始!");
        }
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束!");
        }
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足!");
        }
        Long userId = UserHolder.getUser().getId();
        String userIdStr = userId.toString().intern();
//        synchronized (userIdStr) {
            return createVouvherOrder(voucherId);
//        }

    }


    @Transactional
    public synchronized Result createVouvherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if(count > 0){
                return Result.fail("用户已经购买过了,不允许再下单了!");
            }

            // TODO 判断库存是否充足
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock -1").eq("voucher_id", voucherId)
                    .gt("stock",0)
                    .update();
            if(!success) return Result.ok("扣减失败！");
            //创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            return Result.ok(orderId);

    }
}
