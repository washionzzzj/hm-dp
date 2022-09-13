package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.Test;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;

    @Test
    void testSaveShop(){
        shopService.savaShopToRedis(1L,10L);
    }

}
