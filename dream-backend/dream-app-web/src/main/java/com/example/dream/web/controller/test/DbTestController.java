package com.example.dream.web.controller.test;

import com.example.dream.common.vo.Result;
import com.example.dream.dal.mapper.BizUserMapper;
import com.example.dream.dal.po.BizUserPO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据库连通性测试 Controller
 */
@RestController
@RequestMapping("/api/db-test")
public class DbTestController {

    private final BizUserMapper bizUserMapper;

    public DbTestController(BizUserMapper bizUserMapper) {
        this.bizUserMapper = bizUserMapper;
    }

    /**
     * 插入一条测试用户数据
     */
    @PostMapping("/insert")
    public Result<BizUserPO> insert(@RequestBody(required = false) BizUserPO user) {
        try {
            if (user == null) {
                user = new BizUserPO();
            }
            if (user.getUserId() == null) {
                user.setUserId("test_user_" + System.currentTimeMillis());
            }
            if (user.getPasswordHash() == null) {
                user.setPasswordHash("default_hash");
            }
            if (user.getRole() == null) {
                user.setRole("USER");
            }
            if (user.getStatus() == null) {
                user.setStatus(1);
            }

            bizUserMapper.insert(user);
            return Result.success("插入成功", user);
        } catch (Exception e) {
            return Result.fail("插入失败: " + e.getMessage());
        }
    }

    /**
     * 查询所有用户数据
     */
    @GetMapping("/select")
    public Result<List<BizUserPO>> select() {
        try {
            List<BizUserPO> users = bizUserMapper.selectList(null);
            return Result.success(users);
        } catch (Exception e) {
            return Result.fail("查询失败: " + e.getMessage());
        }
    }

}