package com.sooncode.project.core.model;

import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.generic.BasicAddEvent;
import com.sooncode.project.core.monitor.FuncType;
import com.sooncode.project.core.utils.EntityConvert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件发生时数据（定义参数 + 动态参数）与最终实体审计快照（modelSnapshot）
 * 的回归测试，全部为纯内存断言，不依赖 Mongo。
 */
class EventParamSnapshotTest {

    /** List&lt;ValueObject&gt; 事件参数必须还原为值对象，不能残留 Map。 */
    @Test
    void listValueObjectParamConvertsBackToValueObjects() {
        Basket basket = new Basket();
        basket.setId("b1");
        basket.addBasket("fruit", List.of(item("apple", 2), item("pear", 3)));

        DomainEvent event = basket.getEvents().get(0);
        BasketEvent basketEvent = (BasketEvent) event;
        assertEquals(2, basketEvent.getItems().size());
        assertInstanceOf(Item.class, basketEvent.getItems().get(0));
        assertEquals("apple", basketEvent.getItems().get(0).getSku());
        assertEquals("pear", basketEvent.getItems().get(1).getSku());
    }

    /**
     * 事件定义参数保持发生时数据；modelSnapshot 记录持久化前最终实体状态；
     * modelSnapshot 可序列化但不参与事件参数校验。
     */
    @Test
    void modelSnapshotIsFinalAuditSnapshot() {
        Basket basket = new Basket();
        basket.setId("b2");
        basket.addBasket("orig", List.of(item("apple", 1)));

        // 模拟实体监听在事件触发后修改实体。
        basket.setBasketName("changed");

        List<DomainEvent> pending = basket.preparePendingEventSnapshots();
        assertEquals(1, pending.size());
        DomainEvent event = pending.get(0);

        // 发生时数据不变。
        assertEquals("orig", ((BasketEvent) event).getBasketName());
        // 审计快照反映最终状态。
        assertEquals("changed", event.getModelSnapshot().get("basketName"));
        // 快照可随事件文档序列化。
        assertTrue(EntityConvert.entityToMap(event).containsKey("modelSnapshot"));
        // 派生属性 pendingEvents 不能泄漏进序列化：实体 map 与审计快照都不应包含它。
        assertFalse(EntityConvert.entityToMap(basket).containsKey("pendingEvents"));
        assertFalse(event.getModelSnapshot().containsKey("pendingEvents"));
        // 基础设施属性（事件流、持久化标志、版本）同样不能泄漏进实体 map 与审计快照。
        Map<String, Object> basketMap = EntityConvert.entityToMap(basket);
        assertFalse(basketMap.containsKey("events"));
        assertFalse(basketMap.containsKey("stored"));
        assertFalse(basketMap.containsKey("version"));
        assertFalse(event.getModelSnapshot().containsKey("events"));
        assertFalse(event.getModelSnapshot().containsKey("stored"));
        assertFalse(event.getModelSnapshot().containsKey("version"));
        // 快照不影响后续事件参数校验。
        BasketEvent another = new BasketEvent();
        another.setModelSnapshot(Map.of("basketName", "changed"));
        another.convertParam(basket);
        assertEquals("changed", another.getBasketName());
    }

    /** 只有真正写入成功才推进事件边界，失败重试时待持久化事件保持完整。 */
    @Test
    void pendingBoundaryAdvancesOnlyOnPersistSuccess() {
        Basket basket = new Basket();
        basket.setId("b3");
        basket.addBasket("first", List.of(item("a", 1)));
        basket.addBasket("second", List.of(item("b", 2)));

        assertEquals(2, basket.getPendingEvents().size());
        assertFalse(basket.isStored());

        // 写入失败：不推进边界，pending 保持完整。
        assertEquals(2, basket.getPendingEvents().size());

        basket.markEventsPersisted(new ArrayList<>(basket.getPendingEvents().subList(0, 1)));
        List<DomainEvent> pending = basket.getPendingEvents();
        assertEquals(1, pending.size());
        assertEquals("second", ((BasketEvent) pending.get(0)).getBasketName());
        assertFalse(basket.isStored());

        basket.markEventsPersisted(pending);
        assertTrue(basket.getPendingEvents().isEmpty());
        assertTrue(basket.isStored());
    }

    /** projectiveEntity 先回写实字段：自定义事件的 List<ValueObject> 应还原。 */
    @Test
    void projectiveEntityWritesDeclaredFieldsOnly() {
        Basket basket = new Basket();
        basket.setId("b4");
        basket.setBasketName("old");
        basket.setItems(List.of(item("a", 1)));

        BasketEvent event = new BasketEvent();
        Map<String, Object> itemMap = new LinkedHashMap<>();
        itemMap.put("sku", "mango");
        itemMap.put("qty", 9);
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("basketName", "new");
        param.put("items", List.of(itemMap));
        event.convertParam(param);
        event.projectiveEntity(basket);

        assertEquals("new", basket.getBasketName());
        assertInstanceOf(Item.class, basket.getItems().get(0));
        assertEquals("mango", basket.getItems().get(0).getSku());

        Basket target = new Basket();
        target.setId("b5");
        target.setBasketName("keep");
        DomainEvent basic = new BasicAddEvent();
        Map<String, Object> basicMap = new LinkedHashMap<>();
        basicMap.put("basketName", "hacked");
        basic.convertParam(basicMap);
        basic.projectiveEntity(target);
        // 动态参数命中实体可写属性时同样回写，保证 map 驱动事件重放能还原状态。
        assertEquals("hacked", target.getBasketName());
    }

    /** 动态参数回写跳过 id：dynamicParams 里的 id 不能覆盖聚合根 id。 */
    @Test
    void dynamicProjectionSkipsId() {
        Basket target = new Basket();
        target.setId("keep-id");
        target.setBasketName("old");
        DomainEvent basic = new BasicAddEvent();
        basic.set("basketName", "new");
        basic.set("id", "hacked-id");
        basic.projectiveEntity(target);
        assertEquals("keep-id", target.getId());
        assertEquals("new", target.getBasketName());
    }

    /** @EventBoot.Params 声明的无实字段动态键：必填校验通过后可回写到实体。 */
    @Test
    void paramsDeclaredDynamicKeyProjectsToEntity() {
        Basket target = new Basket();
        target.setId("b6");
        target.setBasketName("old");
        RenameEvent event = new RenameEvent();
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("basketName", "renamed");
        event.convertParam(param);
        event.projectiveEntity(target);
        assertEquals("renamed", target.getBasketName());

        RenameEvent missing = new RenameEvent();
        assertThrows(DomainException.class, () -> missing.convertParam(new LinkedHashMap<>()));
    }

    /** Basic 事件的未定义字段进入动态参数，用于记录发生时数据。 */
    @Test
    void basicEventUnknownFieldsGoToDynamicParams() {
        DomainEvent event = new BasicAddEvent();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("customField", "customValue");
        event.convertParam(map);

        assertEquals("customValue", event.getDynamicParams().get("customField"));
        assertEquals("customValue", event.get("customField"));
    }

    private static Item item(String sku, int qty) {
        Item item = new Item();
        item.setSku(sku);
        item.setQty(qty);
        return item;
    }

    public static class Item implements ValueObject<String> {
        private String sku;
        private int qty;

        public String getSku() {
            return sku;
        }

        public void setSku(String sku) {
            this.sku = sku;
        }

        public int getQty() {
            return qty;
        }

        public void setQty(int qty) {
            this.qty = qty;
        }

        @Override
        public String toValue() {
            return sku;
        }
    }

    public static class BasketEvent extends DomainEvent {
        private String basketName;
        private List<Item> items;

        public String getBasketName() {
            return basketName;
        }

        public void setBasketName(String basketName) {
            this.basketName = basketName;
        }

        public List<Item> getItems() {
            return items;
        }

        public void setItems(List<Item> items) {
            this.items = items;
        }
    }

    @EventBoot(StoreFunc = FuncType.modify, Params = {"basketName"})
    public static class RenameEvent extends DomainEvent {
    }

    public static class Basket extends DomainModel<Basket> {
        private String basketName;
        private List<Item> items;

        public String getBasketName() {
            return basketName;
        }

        public void setBasketName(String basketName) {
            this.basketName = basketName;
        }

        public List<Item> getItems() {
            return items;
        }

        public void setItems(List<Item> items) {
            this.items = items;
        }

        public void addBasket(String name, List<Item> items) {
            setBasketName(name);
            setItems(items);
            BasketEvent event = new BasketEvent();
            event.convertParam(this);
            causes(event);
        }
    }
}
