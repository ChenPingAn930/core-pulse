package com.corepulse.web.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 全局配置
 *
 * <p>MyBatis-Plus 的 selectPage / selectList 等分页方法本身不带分页能力,
 * 必须注册分页内部拦截器(PaginationInnerInterceptor)后,
 * 才会把 Page 参数翻译成对应数据库方言的 LIMIT 子句, 并自动执行 count 查询回填 total。
 * 数据库为 MySQL, 因此指定 DbType.MYSQL。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
