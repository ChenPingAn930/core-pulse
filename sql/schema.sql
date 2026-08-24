-- MySQL dump 10.13  Distrib 8.0.41, for Win64 (x86_64)
--
-- Host: localhost    Database: pc_repair
-- ------------------------------------------------------
-- Server version	8.0.41

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Current Database: `pc_repair`
--

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `pc_repair` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `pc_repair`;

--
-- Table structure for table `t_chat_message`
--

DROP TABLE IF EXISTS `t_chat_message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_chat_message` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `session_id` bigint unsigned NOT NULL,
  `role` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'user/assistant/system/tool',
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `tool_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `task_id` bigint unsigned DEFAULT NULL,
  `extra` json DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=987 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_chat_session`
--

DROP TABLE IF EXISTS `t_chat_session`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_chat_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL DEFAULT '1',
  `title` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '新会话',
  `summary` text COLLATE utf8mb4_unicode_ci COMMENT '会话摘要',
  `last_summarized_message_id` bigint DEFAULT NULL COMMENT '摘要已处理到的最后一条消息ID',
  `status` tinyint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_updated` (`updated_at`)
) ENGINE=InnoDB AUTO_INCREMENT=83 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天会话表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_system_info`
--

DROP TABLE IF EXISTS `t_system_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_system_info` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `os` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `cpu` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ram` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `gpu` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `motherboard` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `disk` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `cpu_load` decimal(5,2) DEFAULT NULL,
  `mem_usage` decimal(5,2) DEFAULT NULL,
  `cpu_temp` decimal(5,2) DEFAULT NULL,
  `disk_health` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='硬件信息快照表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_task_metric`
--

DROP TABLE IF EXISTS `t_task_metric`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_task_metric` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `task_id` bigint unsigned NOT NULL,
  `cpu_temp` decimal(5,2) DEFAULT NULL,
  `gpu_temp` decimal(5,2) DEFAULT NULL,
  `gpu_fps` int DEFAULT NULL,
  `gpu_load` decimal(5,2) DEFAULT NULL,
  `record_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_task_time` (`task_id`,`record_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1206 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务实时指标表(历史)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_tool_task`
--

DROP TABLE IF EXISTS `t_tool_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_tool_task` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `task_type` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `session_id` bigint unsigned DEFAULT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'CREATED',
  `params` json DEFAULT NULL,
  `result` json DEFAULT NULL,
  `progress` tinyint unsigned NOT NULL DEFAULT '0',
  `error_msg` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_session` (`session_id`),
  KEY `idx_type` (`task_type`)
) ENGINE=InnoDB AUTO_INCREMENT=39 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工具任务表(状态机)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_user`
--

DROP TABLE IF EXISTS `t_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名',
  `password_hash` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '密码哈希(BCrypt)',
  `email` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
  `api_key` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'LLM API密钥(掩码存储)',
  `avatar` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '头像URL',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-08-24 19:43:43
