package com.kgc.dm.controller;

import com.kgc.dm.dto.Dto;
import com.kgc.dm.service.OrderService;
import com.kgc.dm.vo.CreateOrderVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    @Autowired
    private OrderService orderService;


    @RequestMapping(value = "/api/v/createOrder",method = RequestMethod.POST)
    public Dto  createOrder(@RequestBody CreateOrderVo createOrderVo)throws Exception
    {
        return orderService.createOrder(createOrderVo);
    }
}
