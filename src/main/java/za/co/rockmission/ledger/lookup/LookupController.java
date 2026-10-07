package za.co.rockmission.ledger.lookup;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only lists the capture form needs. Admin CRUD for these comes in phase 1b. */
@RestController
@RequestMapping("/api/lookups")
public class LookupController {

    private final JdbcTemplate jdbc;

    public LookupController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, List<Map<String, Object>>> all() {
        return Map.of(
            "accounts", jdbc.queryForList("SELECT id, name, type FROM accounts WHERE active ORDER BY name"),
            "funds", jdbc.queryForList("SELECT id, name, restricted FROM funds WHERE active ORDER BY name"),
            "categories", jdbc.queryForList("SELECT id, name, kind FROM categories WHERE active ORDER BY kind, name"));
    }
}
