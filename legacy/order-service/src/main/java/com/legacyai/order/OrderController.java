package com.legacyai.order;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.legacyai.order.Model.Customer;
import com.legacyai.order.Model.Order;
import com.legacyai.order.Model.OrderItem;
import com.legacyai.order.Model.OrderStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 舊系統風格的 API：細碎、以 ID 為主、回傳完整資料。
 * 刻意不為 AI 做任何調整，之後由 adapter pod 負責轉譯。
 */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('cs_agent', 'cs_supervisor')")
public class OrderController {

    private final Map<String, Customer> customers = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public OrderController() {
        seed();
    }

    @GetMapping("/customers")
    public List<Customer> searchCustomers(@RequestParam(required = false) String q) {
        return customers.values().stream()
            .filter(c -> q == null || q.isBlank()
                || c.name().contains(q) || c.phone().contains(q) || c.email().contains(q))
            .sorted(Comparator.comparing(Customer::id))
            .toList();
    }

    @GetMapping("/customers/{id}")
    public Customer getCustomer(@PathVariable String id) {
        Customer c = customers.get(id);
        if (c == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "查無客戶 " + id);
        }
        return c;
    }

    @GetMapping("/orders")
    public List<Order> listOrders(@RequestParam(required = false) String customerId) {
        return orders.values().stream()
            .filter(o -> customerId == null || o.customerId().equals(customerId))
            .sorted(Comparator.comparing(Order::createdAt).reversed())
            .toList();
    }

    @GetMapping("/orders/{id}")
    public Order getOrder(@PathVariable String id) {
        Order o = orders.get(id);
        if (o == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "查無訂單 " + id);
        }
        return o;
    }

    private void seed() {
        LocalDateTime now = LocalDateTime.now().withNano(0);

        put(new Customer("C-001", "王小明", "0912-345-678", "ming.wang@example.com", "GOLD"));
        put(new Customer("C-002", "陳美玲", "0922-111-222", "meiling.chen@example.com", "NORMAL"));
        put(new Customer("C-003", "林大偉", "0933-555-666", "david.lin@example.com", "NORMAL"));

        // 主要 demo 情境：上週買的藍色外套，物流延誤中
        put(new Order("O-2001", "C-001",
            List.of(new OrderItem("JK-BLU-M", "藍色輕量羽絨外套 M", 1, 2980)),
            2980, OrderStatus.SHIPPED, "新北市板橋區文化路一段 100 號 5 樓", "S-3001", now.minusDays(8)));
        put(new Order("O-2002", "C-001",
            List.of(new OrderItem("SK-WHT-26", "白色休閒鞋 26", 1, 1880)),
            1880, OrderStatus.DELIVERED, "新北市板橋區文化路一段 100 號 5 樓", "S-3002", now.minusDays(40)));
        put(new Order("O-2003", "C-002",
            List.of(new OrderItem("BG-BLK-01", "黑色托特包", 1, 1580),
                    new OrderItem("WL-BRN-01", "咖啡色短夾", 1, 990)),
            2570, OrderStatus.SHIPPED, "台中市西屯區台灣大道三段 99 號", "S-3003", now.minusDays(3)));
        put(new Order("O-2004", "C-003",
            List.of(new OrderItem("TS-GRY-L", "灰色素 T L", 3, 490)),
            1470, OrderStatus.PAID, "高雄市苓雅區四維三路 2 號", null, now.minusDays(1)));
    }

    private void put(Customer c) { customers.put(c.id(), c); }

    private void put(Order o) { orders.put(o.id(), o); }
}
