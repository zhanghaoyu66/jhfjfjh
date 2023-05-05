package cn.intellijin.mall.service.impl;

import cn.intellijin.mall.constant.*;
import cn.intellijin.mall.dto.ComboDishDto;
import cn.intellijin.mall.dto.ComboDto;
import cn.intellijin.mall.dto.ComboFlavorDto;
import cn.intellijin.mall.entity.PageResult;
import cn.intellijin.mall.entity.UserMessage;
import cn.intellijin.mall.exception.ApiException;
import cn.intellijin.mall.factory.BeanFactoryMap;
import cn.intellijin.mall.factory.ComboFactory;
import cn.intellijin.mall.factory.IBeanFactory;
import cn.intellijin.mall.mapper.ComboDishMapper;
import cn.intellijin.mall.mapper.ComboFlavorMapper;
import cn.intellijin.mall.mapper.ComboMapper;
import cn.intellijin.mall.pojo.Combo;
import cn.intellijin.mall.pojo.ComboDish;
import cn.intellijin.mall.pojo.ComboFlavor;
import cn.intellijin.mall.service.ComboService;
import cn.intellijin.mall.util.JedisUtil;
import cn.intellijin.mall.util.LockUtil;
import cn.intellijin.mall.util.UserMessageUtil;
import com.alibaba.fastjson.JSON;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;

/**
 * @program: mall
 * @description:
 * @author: Mr.Tan
 * @create: 2022-10-15 20:00
 **/
@Service
@SuppressWarnings("all")
public class ComboServiceImpl implements ComboService {

    @Autowired
    private ComboMapper comboMapper;

    @Autowired
    private ComboDishMapper comboDishMapper;

    @Autowired
    private ComboFlavorMapper comboFlavorMapper;

    @Autowired
    private RestHighLevelClient client;

    @Override
    public boolean isContainDeletingCategory(long categoryId) {
        return comboMapper.findCategoryCount(categoryId) > 0;
    }

    @Override
    public boolean isContainDeletingDish(long dishId) {
        return comboMapper.findRelatedComboCount(dishId) > 0;
    }

    @Override
    @Transactional
    public boolean addCombo(ComboDto comboDto) throws IOException {
        UserMessage userMessage = UserMessageUtil.getUserMessage();
        // 添加套餐
        IBeanFactory comboFactory = BeanFactoryMap.getBeanFactory(Combo.class.getSimpleName());
        Combo combo = (Combo) comboFactory.createPojo(comboDto);
        LockUtil.lock(LockConstant.COMBO_LOCK_KEY, userMessage.getId());
        int i = comboMapper.insert(combo);
        LockUtil.unlock(LockConstant.COMBO_LOCK_KEY);
        // 添加菜品
        IBeanFactory comboDishFactory = BeanFactoryMap.getBeanFactory(ComboDish.class.getSimpleName());
        List<ComboDish> comboDishes = (List<ComboDish>) comboDishFactory.createPojos(comboDto.getComboDishDtos());
        for (int j = 0; j < comboDishes.size(); j++) {
            comboDishes.get(j).setComboId(combo.getId());
        }
        LockUtil.lock(LockConstant.COMBO_DISH_LOCK_KEY, userMessage.getId());
        comboDishMapper.insertList(comboDishes);
        LockUtil.unlock(LockConstant.COMBO_DISH_LOCK_KEY);
        // 添加口味
        IBeanFactory comboFlavorFactory = BeanFactoryMap.getBeanFactory(ComboFlavor.class.getSimpleName());
        List<ComboFlavor> comboFlavors = (List<ComboFlavor>) comboFlavorFactory.createPojos(comboDto.getComboFlavorDtos());
        for (int j = 0; j < comboFlavors.size(); j++) {
            comboFlavors.get(j).setComboId(combo.getId());
        }
        LockUtil.lock(LockConstant.COMBO_FLAVOR_LOCK_KEY, userMessage.getId());
        comboFlavorMapper.insertList(comboFlavors);
        LockUtil.unlock(LockConstant.COMBO_FLAVOR_LOCK_KEY);
        // 删除缓存
        JedisUtil.delKey(CacheConstant.COMBO_DTO + combo.getId());
        // 添加文档
        addDoc(String.valueOf(combo.getId()));
        return i == 1;
    }

    private void addDoc(String id) throws IOException {
        ComboDto combo = getCombo(id);
        String json = JSON.toJSONString(combo);
        IndexRequest request = new IndexRequest(IndexConstant.COMBO)
                .id(id)
                .source(json, XContentType.JSON);
        client.index(request, RequestOptions.DEFAULT);
    }

    @Override
    @Transactional
    public boolean deleteCombo(String strId) throws IOException {
        long id = Long.parseLong(strId);
        ComboDto combo = getCombo(strId);
        UserMessage userMessage = UserMessageUtil.getUserMessage();
        if (userMessage.getShopId() != Long.parseLong(combo.getShopId())) {
            throw new ApiException(ErrorMsgConstant.NOT_AUTHORIZATION);
        }
        // 删除套餐
        LockUtil.lock(LockConstant.COMBO_LOCK_KEY, userMessage.getId());
        int i = comboMapper.deleteByPrimaryKey(id);
        LockUtil.unlock(LockConstant.COMBO_LOCK_KEY);
        // 删除关联菜品
        LockUtil.lock(LockConstant.COMBO_DISH_LOCK_KEY, userMessage.getId());
        comboDishMapper.deleteByComboId(id);
        LockUtil.unlock(LockConstant.COMBO_DISH_LOCK_KEY);
        // 删除口味
        LockUtil.lock(LockConstant.COMBO_FLAVOR_LOCK_KEY, userMessage.getId());
        comboFlavorMapper.deleteByComboId(id);
        LockUtil.unlock(LockConstant.COMBO_FLAVOR_LOCK_KEY);
        // 清除缓存
        JedisUtil.delKey(CacheConstant.COMBO_DTO + id);
        // 删除文档
        deleteDoc(strId);
        return i == 1;
    }

    private void deleteDoc(String id) throws IOException {
        DeleteRequest request = new DeleteRequest()
                .id(id);
        client.delete(request, RequestOptions.DEFAULT);
    }

    @Override
    public boolean updateCombo(ComboDto comboDto) throws IOException {
        UserMessage userMessage = UserMessageUtil.getUserMessage();
        // 删除套餐
        IBeanFactory comboFactory = BeanFactoryMap.getBeanFactory(Combo.class.getSimpleName());
        Combo combo = (Combo) comboFactory.createPojo(comboDto);
        combo.setId(Long.parseLong(comboDto.getId()));
        combo.setCreateUser(null);
        combo.setCreateTime(null);
        LockUtil.lock(LockConstant.COMBO_LOCK_KEY, userMessage.getId());
        int i = comboMapper.updateByPrimaryKeySelective(combo);
        LockUtil.unlock(LockConstant.COMBO_LOCK_KEY);
        // 更新关联菜品
        LockUtil.lock(LockConstant.COMBO_DISH_LOCK_KEY, userMessage.getId());
        comboDishMapper.deleteByComboId(Long.parseLong(comboDto.getId()));
        LockUtil.unlock(LockConstant.COMBO_DISH_LOCK_KEY);

        IBeanFactory comboDishFactory = BeanFactoryMap.getBeanFactory(ComboDish.class.getSimpleName());
        List<ComboDish> comboDishes = (List<ComboDish>) comboDishFactory.createPojos(comboDto.getComboDishDtos());
        LockUtil.lock(LockConstant.COMBO_DISH_LOCK_KEY, userMessage.getId());
        comboDishMapper.insertList(comboDishes);
        LockUtil.unlock(LockConstant.COMBO_DISH_LOCK_KEY);
        // 更新口味
        LockUtil.lock(LockConstant.COMBO_FLAVOR_LOCK_KEY, userMessage.getId());
        comboFlavorMapper.deleteByComboId(Long.parseLong(comboDto.getId()));
        LockUtil.unlock(LockConstant.COMBO_FLAVOR_LOCK_KEY);

        IBeanFactory comboFlavorFactory = BeanFactoryMap.getBeanFactory(ComboFlavor.class.getSimpleName());
        List<ComboFlavor> comboFlavors = (List<ComboFlavor>) comboFlavorFactory.createPojos(comboDto.getComboFlavorDtos());
        LockUtil.lock(LockConstant.COMBO_FLAVOR_LOCK_KEY, userMessage.getId());
        comboFlavorMapper.insertList(comboFlavors);
        LockUtil.unlock(LockConstant.COMBO_FLAVOR_LOCK_KEY);
        // 删除缓存
        JedisUtil.delKey(CacheConstant.COMBO_DTO + comboDto.getId());
        // 更新文档
        addDoc(comboDto.getId());
        return i == 1;
    }

    @Override
    public ComboDto getCombo(String strId) {
        long id = Long.parseLong(strId);
        UserMessage userMessage = UserMessageUtil.getUserMessage();
        ComboDto cache = (ComboDto) JedisUtil.getObject(CacheConstant.COMBO_DTO + id);
        if (cache != null) {
            return cache;
        }
        Combo combo = comboMapper.selectByPrimaryKey(id);
        if (combo == null) {
            JedisUtil.setObject(CacheConstant.COMBO_DTO + id, "", JedisUtil.randomExpireTime());
            throw new ApiException(ErrorMsgConstant.OBJECT_NOT_EXIST);
        }
        if (combo.getShopId() != userMessage.getShopId()) {
            throw new ApiException(ErrorMsgConstant.NOT_AUTHORIZATION);
        }

        IBeanFactory comboFactory = BeanFactoryMap.getBeanFactory(Combo.class.getSimpleName());
        ComboDto comboDto = (ComboDto) comboFactory.createDto(combo);

        List<ComboDish> comboDishes = comboDishMapper.selectByComboId(id);
        IBeanFactory comboDishFactory = BeanFactoryMap.getBeanFactory(ComboDish.class.getSimpleName());
        List<ComboDishDto> comboDishDtos = (List<ComboDishDto>) comboDishFactory.createDtos(comboDishes);

        List<ComboFlavor> comboFlavors = comboFlavorMapper.selectByComboId(id);
        IBeanFactory comboFlavorFactory = BeanFactoryMap.getBeanFactory(ComboFlavor.class.getSimpleName());
        List<ComboFlavorDto> comboFlavorDtos = (List<ComboFlavorDto>) comboFlavorFactory.createDtos(comboFlavors);

        comboDto.setComboDishDtos(comboDishDtos);
        comboDto.setComboFlavorDtos(comboFlavorDtos);

        JedisUtil.setObject(CacheConstant.COMBO_DTO + id, comboDto, JedisUtil.randomExpireTime());

        return comboDto;
    }

    @Override
    public boolean managerStatus(String id, boolean isClose) throws IOException {
        if (isClose) {
            return closeCombo(id);
        } else {
            return openCombo(id);
        }
    }

    private boolean closeCombo(String id) throws IOException {
        ComboDto combo = getCombo(id);
        UserMessage userMessage = UserMessageUtil.getUserMessage();
        if (Long.parseLong(combo.getShopId()) != userMessage.getShopId()) {
            throw new ApiException(ErrorMsgConstant.NOT_AUTHORIZATION);
        }
        if (combo.getIsSelling().equals(ByteConstant.FALSE)) {
            throw new ApiException(ErrorMsgConstant.NOT_MULTI_CLOSE);
        }
        LockUtil.lock(LockConstant.COMBO_LOCK_KEY, userMessage.getId());
        int i = comboMapper.updateSelling(ByteConstant.FALSE, Long.parseLong(id));
        LockUtil.unlock(LockConstant.COMBO_LOCK_KEY);
        // 删除缓存
        JedisUtil.delKey(CacheConstant.COMBO_DTO + id);
        // 更新文档
        updateDoc(true, id);
        return i == 1;
    }

    private boolean openCombo(String id) throws IOException {
        ComboDto combo = getCombo(id);
        UserMessage userMessage = UserMessageUtil.getUserMessage();
        if (Long.parseLong(combo.getShopId()) != userMessage.getShopId()) {
            throw new ApiException(ErrorMsgConstant.NOT_AUTHORIZATION);
        }
        if (combo.getIsSelling().equals(ByteConstant.TRUE)) {
            throw new ApiException(ErrorMsgConstant.NOT_MULTI_OPEN);
        }
        LockUtil.lock(LockConstant.COMBO_LOCK_KEY, userMessage.getId());
        int i = comboMapper.updateSelling(ByteConstant.TRUE, Long.parseLong(id));
        LockUtil.unlock(LockConstant.COMBO_LOCK_KEY);
        // 删除缓存
        JedisUtil.delKey(CacheConstant.COMBO_DTO + id);
        // 更新文档
        updateDoc(false, id);
        return i == 1;
    }

    private void updateDoc(boolean isClose, String id) throws IOException {
        UpdateRequest request = new UpdateRequest(IndexConstant.COMBO, id);
        if (isClose) {
            request.doc("isSelling", ByteConstant.FALSE);
        } else {
            request.doc("isSelling", ByteConstant.TRUE);
        }
        client.update(request, RequestOptions.DEFAULT);
    }

    @Override
    public PageResult<ComboDto> pageQuery(int page, int pageSize, String query) throws IOException {
        int position = (page - 1) * pageSize;
        SearchRequest request = new SearchRequest(IndexConstant.COMBO);
        if (query == null || query.isEmpty()) {
            request.source()
                    .query(QueryBuilders.matchAllQuery())
                    .from(position).size(pageSize)
                    .sort("id", SortOrder.ASC);
        } else {
            request.source()
                    .query(QueryBuilders.matchQuery("all", query))
                    .from(position).size(pageSize)
                    .sort("id", SortOrder.ASC);
        }
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        ComboFactory comboFactory = (ComboFactory) BeanFactoryMap.getBeanFactory(Combo.class.getSimpleName());
        List<ComboDto> comboDtos = (List<ComboDto>) comboFactory.dealWithResponse(response);
        PageResult<ComboDto> pageResult = getPageResult(page, pageSize);
        pageResult.setList(comboDtos);
        return pageResult;
    }

    private PageResult<ComboDto> getPageResult(int page, int pageSize) {
        UserMessage userMessage = UserMessageUtil.getUserMessage();
        int count = comboMapper.findCount(userMessage.getShopId());
        PageResult<ComboDto> pageResult = new PageResult<>();
        pageResult.setPage(page);
        pageResult.setPageSize(pageSize);
        pageResult.setSum(count);
        pageResult.setSumPage(count / pageSize + 1);
        return pageResult;
    }
}
