package cn.intellijin.mall.service.impl;

import cn.intellijin.mall.constant.LockConstant;
import cn.intellijin.mall.dto.OrderComboDto;
import cn.intellijin.mall.dto.OrderDishDto;
import cn.intellijin.mall.dto.OrderDetailDto;
import cn.intellijin.mall.entity.PageResult;
import cn.intellijin.mall.entity.UserMessage;
import cn.intellijin.mall.exception.ApiException;
import cn.intellijin.mall.factory.BeanFactoryMap;
import cn.intellijin.mall.factory.IBeanFactory;
import cn.intellijin.mall.mapper.OrderComboMapper;
import cn.intellijin.mall.mapper.OrderDetailMapper;
import cn.intellijin.mall.mapper.OrderDishMapper;
import cn.intellijin.mall.pojo.OrderCombo;
import cn.intellijin.mall.pojo.OrderDetail;
import cn.intellijin.mall.pojo.OrderDish;
import cn.intellijin.mall.service.OrderService;
import cn.intellijin.mall.util.LockUtil;
import cn.intellijin.mall.util.RedisIdGeneratorUtil;
import cn.intellijin.mall.util.UserMessageUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @program: mall
 * @description:
 * @author: Mr.Tan
 * @create: 2022-11-08 09:15
 **/
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private OrderDishMapper orderDishMapper;
    @Autowired
    private OrderComboMapper orderComboMapper;

    @Override
    @Transactional
    public boolean addOrder(OrderDetailDto orderDetailDto) {
        UserMessage userMessage = UserMessageUtil.getUserMessage();
        IBeanFactory orderDetailFactory = BeanFactoryMap.getBeanFactory(OrderDetail.class.getSimpleName());
        OrderDetail orderDetail = (OrderDetail)orderDetailFactory.createPojo(orderDetailDto);

        LockUtil.lock(LockConstant.ORDER_DETAIL_LOCK_KEY,userMessage.getId());
        int i=orderDetailMapper.insert(orderDetail);
        LockUtil.unlock(LockConstant.ORDER_DETAIL_LOCK_KEY);
        //插入OrderDish
        List<OrderDishDto> orderDishDtos = orderDetailDto.getOrderDish();
        List<OrderDish> orderDishes=new ArrayList<>();
        for(OrderDishDto orderDishDto:orderDishDtos){
            OrderDish orderDish = new OrderDish();
            orderDish.setId(RedisIdGeneratorUtil.nextId(OrderDish.class.getSimpleName()));
            orderDish.setOrderId(orderDish.getId());
            orderDish.setDishId(Long.valueOf(orderDishDto.getDishId()));
            orderDish.setFlavor(orderDishDto.getFlavor());
            orderDish.setAmount(orderDishDto.getAmount());
            orderDish.setMoney(orderDishDto.getMoney());
            orderDish.setCreateUser(userMessage.getId());
            orderDish.setCreateTime(new Date());
            orderDish.setUpdateTime(new Date());
            orderDish.setUpdateUser(userMessage.getId());
            orderDishes.add(orderDish);
        }
        LockUtil.lock(LockConstant.ORDER_DISH_LOCK_KEY,userMessage.getId());
        int j=orderDishMapper.insertList(orderDishes);
        LockUtil.unlock(LockConstant.ORDER_DISH_LOCK_KEY);
        //插入orderCombo
        List<OrderComboDto> orderComboDtos=orderDetailDto.getOrderCombo();
        List<OrderCombo> orderCombos=new ArrayList<>();
        for(OrderComboDto comboDto:orderComboDtos){
            OrderCombo orderCombo = new OrderCombo();
            orderCombo.setId(RedisIdGeneratorUtil.nextId(OrderCombo.class.getSimpleName()));
            orderCombo.setComboId(Long.valueOf(comboDto.getComboId()));
            orderCombo.setOrderId(orderDetail.getId());
            orderCombo.setAmount(comboDto.getAmount());
            orderCombo.setMoney(comboDto.getMoney());
            orderCombo.setFlavor(comboDto.getFlavor());
            orderCombo.setCreateTime(new Date());
            orderCombo.setUpdateTime(new Date());
            orderCombo.setCreateUser(userMessage.getId());
            orderCombo.setUpdateUser(userMessage.getId());
            orderCombos.add(orderCombo);
        }
        LockUtil.lock(OrderCombo.class.getSimpleName(), userMessage.getId());
        int k=orderComboMapper.insertList(orderCombos);
        LockUtil.unlock(OrderCombo.class.getSimpleName());
        return i==1 && j==orderDishDtos.size() && k==orderComboDtos.size();
    }

    @Override
    public PageResult<OrderDetailDto> pageQuery(int page, int pageSize) {
        UserMessage userMessage = UserMessageUtil.getUserMessage();
        long shopId = userMessage.getShopId();
        List<OrderDetail> orderDetails = orderDetailMapper.pageQuery((page - 1) * pageSize, pageSize,shopId);
        IBeanFactory orderDetailFactory = BeanFactoryMap.getBeanFactory(OrderDetail.class.getSimpleName());
        List<OrderDetailDto> dtos =(List<OrderDetailDto>) orderDetailFactory.createDtos(orderDetails);
        for(OrderDetailDto dto:dtos){
            Long orderId=Long.valueOf(dto.getId());
            //添加orderDish
            List<OrderDish> orderDishes=orderDishMapper.selectByOrderId(orderId);
            List<OrderDishDto> orderDishDtos=new ArrayList<>();
            for(OrderDish orderDish:orderDishes){
                OrderDishDto orderDishDto = new OrderDishDto();
                orderDishDto.setDishId(orderDish.getDishId().toString());
                orderDishDto.setAmount(orderDish.getAmount());
                orderDishDto.setFlavor(orderDish.getFlavor());
                orderDishDto.setMoney(orderDish.getMoney());
                orderDishDtos.add(orderDishDto);
            }
            dto.setOrderDish(orderDishDtos);
            //天剑orderCombo
            List<OrderCombo> orderCombos=orderComboMapper.selectByOrderId(orderId);
            List<OrderComboDto> orderComboDtos = new ArrayList<>();
            for(OrderCombo orderCombo:orderCombos){
                OrderComboDto comboDto = new OrderComboDto();
                comboDto.setComboId(orderCombo.getComboId().toString());
                comboDto.setAmount(orderCombo.getAmount());
                comboDto.setFlavor(orderCombo.getFlavor());
                comboDto.setMoney(orderCombo.getMoney());
                orderComboDtos.add(comboDto);
            }
            dto.setOrderCombo(orderComboDtos);
        }
        PageResult<OrderDetailDto> pageResult = new PageResult<>();
        pageResult.setPage(page);
        pageResult.setPageSize(pageSize);
        int count =orderDetailMapper.findCount();
        pageResult.setSum(count);
        pageResult.setSumPage(count/pageSize+1);
        pageResult.setList(dtos);
        return pageResult;
    }

    @Override
    public boolean updateStatus(OrderDetailDto orderDetailDto) {
        Integer status=orderDetailDto.getStatus();
        Long id=Long.valueOf(orderDetailDto.getId());
        int i = orderDetailMapper.updateStatus(status, Long.valueOf(id));
        return i==1;
    }

    @Override
    public List<OrderDetailDto> getOrderByCustomerId(Long customerId) {
        List<OrderDetail> orderDetails=orderDetailMapper.selectByCustomerId(customerId);
        IBeanFactory orderDetailFactory = BeanFactoryMap.getBeanFactory(OrderDetail.class.getSimpleName());
        List<OrderDetailDto> dtos =(List<OrderDetailDto>) orderDetailFactory.createDtos(orderDetails);
        for(OrderDetailDto dto:dtos){
            Long orderId=Long.valueOf(dto.getId());
            //添加orderDish
            List<OrderDish> orderDishes=orderDishMapper.selectByOrderId(orderId);
            List<OrderDishDto> orderDishDtos=new ArrayList<>();
            for(OrderDish orderDish:orderDishes){
                OrderDishDto orderDishDto = new OrderDishDto();
                orderDishDto.setDishId(orderDish.getDishId().toString());
                orderDishDto.setAmount(orderDish.getAmount());
                orderDishDto.setFlavor(orderDish.getFlavor());
                orderDishDto.setMoney(orderDish.getMoney());
                orderDishDtos.add(orderDishDto);
            }
            dto.setOrderDish(orderDishDtos);
            //添加剑orderCombo
            List<OrderCombo> orderCombos=orderComboMapper.selectByOrderId(orderId);
            List<OrderComboDto> orderComboDtos = new ArrayList<>();
            for(OrderCombo orderCombo:orderCombos){
                OrderComboDto comboDto = new OrderComboDto();
                comboDto.setComboId(orderCombo.getComboId().toString());
                comboDto.setAmount(orderCombo.getAmount());
                comboDto.setFlavor(orderCombo.getFlavor());
                comboDto.setMoney(orderCombo.getMoney());
                orderComboDtos.add(comboDto);
            }
            dto.setOrderCombo(orderComboDtos);
        }
        return dtos;
    }

    @Override
    public OrderDetailDto createOrder(OrderDetailDto order) {
        UserMessage userMessage = UserMessageUtil.getUserMessage();
        IBeanFactory orderDetailFactory = BeanFactoryMap.getBeanFactory(OrderDetail.class.getSimpleName());
        OrderDetail orderDetail = (OrderDetail)orderDetailFactory.createPojo(order);

        LockUtil.lock(LockConstant.ORDER_DETAIL_LOCK_KEY,userMessage.getId());
        int i=orderDetailMapper.insert(orderDetail);
        LockUtil.unlock(LockConstant.ORDER_DETAIL_LOCK_KEY);
        //插入OrderDish
        List<OrderDishDto> orderDishDtos = order.getOrderDish();
        List<OrderDish> orderDishes=new ArrayList<>();
        for(OrderDishDto orderDishDto:orderDishDtos){
            OrderDish orderDish = new OrderDish();
            orderDish.setId(RedisIdGeneratorUtil.nextId(OrderDish.class.getSimpleName()));
            orderDish.setOrderId(orderDish.getId());
            orderDish.setDishId(Long.valueOf(orderDishDto.getDishId()));
            orderDish.setFlavor(orderDishDto.getFlavor());
            orderDish.setAmount(orderDishDto.getAmount());
            orderDish.setMoney(orderDishDto.getMoney());
            orderDish.setCreateUser(userMessage.getId());
            orderDish.setCreateTime(new Date());
            orderDish.setUpdateTime(new Date());
            orderDish.setUpdateUser(userMessage.getId());
            orderDishes.add(orderDish);
        }
        LockUtil.lock(LockConstant.ORDER_DISH_LOCK_KEY,userMessage.getId());
        int j=orderDishMapper.insertList(orderDishes);
        LockUtil.unlock(LockConstant.ORDER_DISH_LOCK_KEY);
        //插入orderCombo
        List<OrderComboDto> orderComboDtos=order.getOrderCombo();
        List<OrderCombo> orderCombos=new ArrayList<>();
        for(OrderComboDto comboDto:orderComboDtos){
            OrderCombo orderCombo = new OrderCombo();
            orderCombo.setId(RedisIdGeneratorUtil.nextId(OrderCombo.class.getSimpleName()));
            orderCombo.setComboId(Long.valueOf(comboDto.getComboId()));
            orderCombo.setOrderId(orderDetail.getId());
            orderCombo.setAmount(comboDto.getAmount());
            orderCombo.setMoney(comboDto.getMoney());
            orderCombo.setFlavor(comboDto.getFlavor());
            orderCombo.setCreateTime(new Date());
            orderCombo.setUpdateTime(new Date());
            orderCombo.setCreateUser(userMessage.getId());
            orderCombo.setUpdateUser(userMessage.getId());
            orderCombos.add(orderCombo);
        }
        LockUtil.lock(OrderCombo.class.getSimpleName(), userMessage.getId());
        int k=orderComboMapper.insertList(orderCombos);
        LockUtil.unlock(OrderCombo.class.getSimpleName());
        if (i==1 && j==orderDishDtos.size() && k==orderComboDtos.size()) {
            return order;
        }
        throw new ApiException("订单插入失败");
    }

    @Override
    public int getOrderStatus(long id) {
        OrderDetail orderDetail = orderDetailMapper.selectByPrimaryKey(id);
        return orderDetail.getStatus();
    }

    @Override
    public void updateOrderStatus(long id) {
        orderDetailMapper.updateOrderStatus(id);
    }
}
