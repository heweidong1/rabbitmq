package com.kgc.dm.service;

import com.kgc.dm.dto.Dto;
import com.kgc.dm.vo.CreateOrderVo;

public interface OrderService
{
    public Dto createOrder(CreateOrderVo createOrderVo)throws Exception;
}
