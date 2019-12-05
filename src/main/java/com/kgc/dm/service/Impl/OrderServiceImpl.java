package com.kgc.dm.service.Impl;

import com.kgc.dm.client.*;
import com.kgc.dm.dto.Dto;
import com.kgc.dm.dto.DtoUtil;
import com.kgc.dm.pojo.DmOrder;
import com.kgc.dm.pojo.DmOrderLinkUser;
import com.kgc.dm.pojo.DmSchedulerSeat;
import com.kgc.dm.pojo.DmSchedulerSeatPrice;
import com.kgc.dm.service.OrderService;
import com.kgc.dm.utils.common.Constants;
import com.kgc.dm.utils.common.IdWorker;
import com.kgc.dm.vo.CreateOrderVo;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class OrderServiceImpl implements OrderService
{
    @Autowired
    private RestDmSchedulerClient restDmSchedulerClient;

    @Autowired
    private RestDmOrderLinkUserClient restDmOrderLinkUserClient;

    @Autowired
    private RestDmSchedulerSeatClient restDmSchedulerSeatClient;

    @Autowired
    private RestDmOrderClient restDmOrderClient;

    @Autowired
    private RestDmSchedulerSeatPriceClient restDmSchedulerSeatPriceClient;


    @Autowired
    private RestDmItemClient restDmItemClient;

    @Autowired
    private RestDmLinkUserClient restDmLinkUserClient;


    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Override
    public Dto createOrder(CreateOrderVo createOrderVo) throws Exception {

        //这条订单有多少座位
        String[] seats = createOrderVo.getSeatPositions().split(",");
        //订单总价格
        double totalPrice =0;
        //一个人对应一个座位   一个价格
        double[]doubles = new double[seats.length];
        //订单号
        String orderNo = IdWorker.getId();
        for(int i = 0;i<seats.length;i++)
        {
            String[] s = seats[i].split("_");
            Map<String,Object> param = new HashMap<>();
            param.put("schedulerId",createOrderVo.getSchedulerId());
            param.put("x",s[0]);
            param.put("y",s[1]);
            DmSchedulerSeat dmSchedulerSeat = restDmSchedulerSeatClient.getDmSchedulerSeatListByMap(param).get(0);
            dmSchedulerSeat.setUserId(createOrderVo.getUserId());
            dmSchedulerSeat.setOrderNo(orderNo);
            dmSchedulerSeat.setStatus(Constants.OrderStatus.SUCCESS);
            //修改当前座位的状态
            restDmSchedulerSeatClient.qdtxModifyDmSchedulerSeat(dmSchedulerSeat);
            Map<String,Object>getPrice = new HashMap<>();
            getPrice.put("schedulerId",createOrderVo.getSchedulerId());
            getPrice.put("areaLevel",dmSchedulerSeat.getAreaLevel());
            //获取该座位的价格
            DmSchedulerSeatPrice dmSchedulerSeatPrice = restDmSchedulerSeatPriceClient.getDmSchedulerSeatPriceListByMap(getPrice).get(0);
            //计算总价
            totalPrice+=dmSchedulerSeatPrice.getPrice();
            //添加数据到座位数组
            doubles[i] = dmSchedulerSeatPrice.getPrice();

        }
        //下订单
        DmOrder dmOrder
                 = new DmOrder();
        BeanUtils.copyProperties(createOrderVo,dmOrder);
        dmOrder.setItemName(restDmItemClient.getDmItemById(createOrderVo.getItemId()).getItemName());
        dmOrder.setCreatedTime(new Date());
        if(createOrderVo.getIsNeedInsurance()==Constants.OrderStatus.ISNEEDINSURANCE_YES)
        {
            totalPrice+=20.0D;
        }
        dmOrder.setTotalAmount(totalPrice);
        dmOrder.setOrderNo(orderNo);
        //支付状态
        dmOrder.setOrderType(Constants.OrderStatus.TOPAY);
        try
        {
            restDmOrderClient.qdtxAddDmOrder(dmOrder);

        }catch (Exception e)
        {
            e.printStackTrace();
            //向rabbitmq发送消息
            Map<String,Object> reSetSeat = new HashMap<>();
            reSetSeat.put("schedulerId",createOrderVo.getSchedulerId());
            reSetSeat.put("seats",seats);
            rabbitTemplate.convertAndSend(Constants.RabbitQueueName.TOPIC_EXCHANGE,
                    //key
                    Constants.RabbitQueueName.TO_RESET_SEAT_QUQUE, reSetSeat,
                    //持久化
                    new MessagePostProcessor() {
                        @Override
                        public Message postProcessMessage(Message message) throws AmqpException {
                            //将发过来的信息 持久化
                            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                            return message;
                        }
                    });
        }




        //往相关练习人 表 中 插入数据
        String[] split = createOrderVo.getLinkIds().split(",");
        for(int i =0;i<split.length;i++)
        {
            DmOrderLinkUser dmOrderLinkUserById = restDmOrderLinkUserClient.getDmOrderLinkUserById(Long.parseLong(split[i]));
            DmOrderLinkUser dmOrderLinkUser = new DmOrderLinkUser();
            dmOrderLinkUser.setCreatedTime(new Date());
            dmOrderLinkUser.setLinkUserId(Long.getLong(split[i]));
            dmOrderLinkUser.setPrice(doubles[i]);
            dmOrderLinkUser.setX(Integer.parseInt(seats[i].split("_")[0]));
            dmOrderLinkUser.setY(Integer.parseInt(seats[i].split("_")[1]));
            dmOrderLinkUser.setOrderId(dmOrder.getId());
            dmOrderLinkUser.setLinkUserName(dmOrderLinkUserById.getLinkUserName());
        }
        return DtoUtil.returnDataSuccess(orderNo);
    }


    @RabbitListener(queues = Constants.RabbitQueueName.TO_RESET_SEAT_QUQUE)
    public void reSetseats(Message message, Map<String,Object> seat, Channel channel) throws Exception {
        Long schedulerId=(Long)seat.get("schedulerId");
        String[] seats = (String[])seat.get("seats");
        for(int i = 0;i<seats.length;i++)
        {
            String[] s = seats[i].split("_");
            Map<String,Object> param = new HashMap<>();
            param.put("schedulerId",schedulerId);
            param.put("x",s[0]);
            param.put("y",s[1]);
            DmSchedulerSeat dmSchedulerSeat = restDmSchedulerSeatClient.getDmSchedulerSeatListByMap(param).get(0);
            dmSchedulerSeat.setUserId(null);
            dmSchedulerSeat.setOrderNo(null);
            dmSchedulerSeat.setStatus(Constants.SchedulerSeatStatus.SchedulerSeat_FREE);
            //修改当前座位的状态
            try{
                restDmSchedulerSeatClient.qdtxModifyDmSchedulerSeat(dmSchedulerSeat);
            }catch (Exception e)
            {
                e.printStackTrace();
                //加入死信队列    如果消费不成功，在加入到队列中                      是否批量消费  已经被消费的是否加入到队列
                channel.basicNack(message.getMessageProperties().getDeliveryTag(),false,false);
            }
        }
    }
}
