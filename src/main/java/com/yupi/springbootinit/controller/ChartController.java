package com.yupi.springbootinit.controller;

import cn.hutool.core.io.FileUtil;
import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.exception.ThrowUtils;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.manager.RedisLimitManager;
import com.yupi.springbootinit.model.dto.chart.GenChartByAiRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.vo.BiResponse;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.service.UserService;
import com.yupi.springbootinit.utils.ExcelUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tomcat.util.http.ResponseUtil;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;

import static com.yupi.springbootinit.constant.CommonConstant.BI_MODEL_ID;

public class ChartController {

    private final long DEFAULT_MAXIMUM_FILE_SIZE = 1024 * 1024L;
    private final List<String> validFileSuffixList = Arrays.asList("xlsx");

    @Resource
    private UserService userService;
    @Resource
    private RedisLimitManager redisLimitManager;
    @Resource
    private AiManager aiManager;
    @Resource
    private ChartService chartService;

    public BaseResponse<BiResponse> genChartByAi(@RequestPart("file") MultipartFile multipartFile,
                                                 GenChartByAiRequest AiRequest, HttpServletRequest request) {

        // 获取输入参数并校验
        String name = AiRequest.getName();
        String goal = AiRequest.getGoal();
        String chartType = AiRequest.getChartType();
        ThrowUtils.throwIf(StringUtils.isBlank(goal), ErrorCode.PARAMS_ERROR, "目标为空");
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100, ErrorCode.PARAMS_ERROR, "名称过长");
        ThrowUtils.throwIf(multipartFile.getSize() > DEFAULT_MAXIMUM_FILE_SIZE, ErrorCode.PARAMS_ERROR, "文件不得超过 1M");
        String suffix = FileUtil.getSuffix(multipartFile.getName());
        ThrowUtils.throwIf(!validFileSuffixList.contains(suffix), ErrorCode.PARAMS_ERROR, "文件名后缀非法");

        // 限流：每个用户一个限流器 -> 使用redis
        User loginUser = userService.getLoginUser(request);
        redisLimitManager.doRateLimit("genChartByAi_" + loginUser.getId());

        // 构造用户输入(模版)
        // 分析需求：
        // 分析网站用户的增长情况
        // 原始数据：
        // 日期,用户数
        // 1号,10
        // 2号,20
        // 3号,30
        StringBuilder userInput = new StringBuilder();
        userInput.append("分析需求：").append("\n");

        String userGoal = goal;
        if (StringUtils.isNotBlank(chartType)){
            userGoal += "，请使用 "+chartType;
        }
        userInput.append(userGoal).append("\n");
        userInput.append("原始数据：").append("\n");
        // 使用压缩后的数据 -> 使用csv
        String csvData = ExcelUtils.excelToCsv(multipartFile);
        userInput.append(csvData).append("\n");
        // 发送请求
        String result = aiManager.doChat(BI_MODEL_ID, userInput.toString());
        // 解析请求
        String[] splits = result.split("【【【【【");
        if (splits.length < 3){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成错误");
        }
        String genChart = splits[1].trim();
        String genResult = splits[2].trim();
        // 插入到数据库
        Chart chart = new Chart();
        chart.setName(name);
        chart.setGoal(goal);
        chart.setChartData(csvData);
        chart.setChartType(chartType);
        chart.setGenChart(genChart);
        chart.setGenResult(genResult);
        chart.setUserId(loginUser.getId());
        boolean isSave = chartService.save(chart);
        ThrowUtils.throwIf(!isSave, ErrorCode.SYSTEM_ERROR, "图表保存失败");
        // 生成结果返回前端
        BiResponse biResponse = new BiResponse();
        biResponse.setGenChart(genChart);
        biResponse.setGenResult(genResult);
        biResponse.setChartId(chart.getId());
        return ResultUtils.success(biResponse);
    }
}
