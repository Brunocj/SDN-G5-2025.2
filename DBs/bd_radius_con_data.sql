-- MySQL dump 10.16  Distrib 10.1.48-MariaDB, for debian-linux-gnu (x86_64)
--
-- Host: localhost    Database: radius
-- ------------------------------------------------------
-- Server version	10.1.48-MariaDB-0ubuntu0.18.04.1

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `nas`
--

DROP TABLE IF EXISTS `nas`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `nas` (
  `id` int(10) NOT NULL AUTO_INCREMENT,
  `nasname` varchar(128) NOT NULL,
  `shortname` varchar(32) DEFAULT NULL,
  `type` varchar(30) DEFAULT 'other',
  `ports` int(5) DEFAULT NULL,
  `secret` varchar(60) NOT NULL DEFAULT 'secret',
  `server` varchar(64) DEFAULT NULL,
  `community` varchar(50) DEFAULT NULL,
  `description` varchar(200) DEFAULT 'RADIUS Client',
  PRIMARY KEY (`id`),
  KEY `nasname` (`nasname`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `nas`
--

LOCK TABLES `nas` WRITE;
/*!40000 ALTER TABLE `nas` DISABLE KEYS */;
INSERT INTO `nas` VALUES (1,'192.168.201.0/24','red_gestion','other',NULL,'testing123',NULL,NULL,'Red de Gestion SDN');
/*!40000 ALTER TABLE `nas` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `radacct`
--

DROP TABLE IF EXISTS `radacct`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `radacct` (
  `radacctid` bigint(21) NOT NULL AUTO_INCREMENT,
  `acctsessionid` varchar(64) NOT NULL DEFAULT '',
  `acctuniqueid` varchar(32) NOT NULL DEFAULT '',
  `username` varchar(64) NOT NULL DEFAULT '',
  `realm` varchar(64) DEFAULT '',
  `nasipaddress` varchar(15) NOT NULL DEFAULT '',
  `nasportid` varchar(15) DEFAULT NULL,
  `nasporttype` varchar(32) DEFAULT NULL,
  `acctstarttime` datetime DEFAULT NULL,
  `acctupdatetime` datetime DEFAULT NULL,
  `acctstoptime` datetime DEFAULT NULL,
  `acctinterval` int(12) DEFAULT NULL,
  `acctsessiontime` int(12) unsigned DEFAULT NULL,
  `acctauthentic` varchar(32) DEFAULT NULL,
  `connectinfo_start` varchar(50) DEFAULT NULL,
  `connectinfo_stop` varchar(50) DEFAULT NULL,
  `acctinputoctets` bigint(20) DEFAULT NULL,
  `acctoutputoctets` bigint(20) DEFAULT NULL,
  `calledstationid` varchar(50) NOT NULL DEFAULT '',
  `callingstationid` varchar(50) NOT NULL DEFAULT '',
  `acctterminatecause` varchar(32) NOT NULL DEFAULT '',
  `servicetype` varchar(32) DEFAULT NULL,
  `framedprotocol` varchar(32) DEFAULT NULL,
  `framedipaddress` varchar(15) NOT NULL DEFAULT '',
  PRIMARY KEY (`radacctid`),
  UNIQUE KEY `acctuniqueid` (`acctuniqueid`),
  KEY `username` (`username`),
  KEY `framedipaddress` (`framedipaddress`),
  KEY `acctsessionid` (`acctsessionid`),
  KEY `acctsessiontime` (`acctsessiontime`),
  KEY `acctstarttime` (`acctstarttime`),
  KEY `acctinterval` (`acctinterval`),
  KEY `acctstoptime` (`acctstoptime`),
  KEY `nasipaddress` (`nasipaddress`)
) ENGINE=InnoDB AUTO_INCREMENT=124 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `radacct`
--

LOCK TABLES `radacct` WRITE;
/*!40000 ALTER TABLE `radacct` DISABLE KEYS */;
INSERT INTO `radacct` VALUES (1,'juan_sql-127.0.0.1','f0c37c1f5bd5f77980d48069af23db52','juan_sql','','127.0.0.1','','','2025-11-24 08:35:49','2025-11-24 08:35:49',NULL,NULL,0,'','','',0,0,'','','','','','127.0.0.1'),(2,'alumno01-127.0.0.1','f8cadf2aaaf18d89b9c1e38d6009558e','alumno01','','127.0.0.1','','','2025-11-25 02:40:31','2025-11-25 02:40:31','2025-11-25 02:41:29',NULL,NULL,'','','',0,0,'','','','','','127.0.0.1'),(3,'admin01-127.0.0.1','e5b2c433722991605252e0fc38b7828f','admin01','','127.0.0.1','','','2025-11-28 00:28:38','2025-11-28 00:28:38','2025-11-28 00:27:47',NULL,NULL,'','','',0,0,'','','','','','127.0.0.1'),(4,'investigador_x-127.0.0.1','219d57f1a5bafa45ca2a3071b698791f','investigador_x','','127.0.0.1','','','2025-11-24 21:31:50','2025-11-24 21:31:50',NULL,NULL,0,'','','',0,0,'','','','','','127.0.0.1'),(9,'investigador_1-127.0.0.1','040c9e9239ce3a94b3b69c562428d2c0','investigador_1','','127.0.0.1','','','2025-11-25 02:17:37','2025-11-25 02:17:37','2025-11-25 02:17:42',NULL,NULL,'','','',0,0,'','','','','','127.0.0.1'),(11,'super_profe-127.0.0.1','48fdaf21b625cf9e429fa8483cfba7b2','super_profe','','127.0.0.1','','','2025-11-25 19:00:06','2025-11-25 19:00:06','2025-11-25 19:00:12',NULL,NULL,'','','',0,0,'','','','','','127.0.0.1'),(17,'multirol-127.0.0.1','2d9c226dfc8344618221c8302d9c8828','multirol','','127.0.0.1','','','2025-11-25 20:47:57','2025-11-25 20:47:57','2025-11-25 20:48:02',NULL,NULL,'','','',0,0,'','','','','','127.0.0.1'),(27,'hola@gmail.com-127.0.0.1','b321db287cbedabf6b04c9b5e72361c4','hola@gmail.com','','127.0.0.1','','','2025-11-27 22:31:13','2025-11-27 22:31:13','2025-11-27 22:31:16',NULL,NULL,'','','',0,0,'','','','','','127.0.0.1'),(31,'admin01-10.0.0.51','231ae0386a0fa91ec4f5b1250be8d9f7','admin01','','127.0.0.1','','','2025-11-28 02:01:24','2025-11-28 02:01:24',NULL,NULL,0,'','','',0,0,'','','','','','10.0.0.51'),(34,'super_profe-10.0.0.53','ced448a498be9e8ffcaf10a357b4a681','super_profe','','127.0.0.1','','','2025-11-30 07:46:57','2025-11-30 07:46:57','2025-11-30 07:50:10',NULL,NULL,'','','',0,0,'','','','','','10.0.0.53'),(35,'alumno01-10.0.0.53','2aa102bdd8969f2a3f59d569f223f66e','alumno01','','127.0.0.1','','','2025-11-30 08:01:55','2025-11-30 08:01:55','2025-11-30 08:09:35',NULL,NULL,'','','',0,0,'','','','','','10.0.0.53'),(36,'admin01-10.0.0.53','88e55542bde25e8e6baad7228877551b','admin01','','127.0.0.1','','','2025-11-30 08:32:09','2025-11-30 08:32:09','2025-11-30 09:25:22',NULL,NULL,'','','',0,0,'','','','','','10.0.0.53'),(38,'admin@uni.edu-10.0.0.51','66893424bcac07a06b93eaedc9a00b78','admin@uni.edu','','127.0.0.1','','','2025-11-30 10:55:26','2025-11-30 10:55:26','2025-11-30 10:55:46',NULL,NULL,'','','',0,0,'','','','','','10.0.0.51'),(39,'prof@uni.edu-10.0.0.51','2ad5b2a8486ab5a5c68d95b2fcf2745b','prof@uni.edu','','127.0.0.1','','','2025-11-30 10:21:39','2025-11-30 10:21:39','2025-11-30 10:21:46',NULL,NULL,'','','',0,0,'','','','','','10.0.0.51'),(40,'alumno@uni.edu-10.0.0.51','ddc79c92a75e268bb85b74aa8d38a151','alumno@uni.edu','','127.0.0.1','','','2025-11-30 10:12:10','2025-11-30 10:12:10','2025-11-30 10:20:31',NULL,NULL,'','','',0,0,'','','','','','10.0.0.51'),(48,'a20202396@pucp.edu.pe-10.0.0.51','3d40b7bff23787e2d7f9aa3c3c51afd6','a20202396@pucp.edu.pe','','127.0.0.1','','','2025-12-05 20:56:46','2025-12-05 20:56:46','2025-12-05 20:53:02',NULL,NULL,'','','',0,0,'','','','','','10.0.0.51'),(49,'a20202396@pucp.edu.pe-10.0.0.53','bb02721a583dc4dd1a1c1ce632b36343','a20202396@pucp.edu.pe','','127.0.0.1','','','2025-12-05 05:50:31','2025-12-05 05:50:31','2025-12-05 05:52:02',NULL,NULL,'','','',0,0,'','','','','','10.0.0.53'),(72,'a20202396@pucp.edu.pe-10.0.0.55','33f26d40c4cba7ca18ba5b6a823c3baf','a20202396@pucp.edu.pe','','127.0.0.1','','','2025-12-05 12:31:03','2025-12-05 12:31:03','2025-12-05 19:54:28',NULL,NULL,'','','',0,0,'','','','','','10.0.0.55'),(78,'a20202396@pucp.edu.pe-192.168.201.211','ba96ce626a1f4f1b3d7af239a4a5151a','a20202396@pucp.edu.pe','','127.0.0.1','','','2025-12-04 22:48:05','2025-12-04 22:48:05','2025-12-05 19:52:36',NULL,NULL,'','','',0,0,'','','','','','192.168.201.211'),(87,'csantivanez-10.0.0.51','e7c5f7f6ccb8a5ddbfd4a7f1a8415838','csantivanez','','127.0.0.1','','','2025-12-05 20:49:52','2025-12-05 20:49:52','2025-12-05 20:50:49',NULL,NULL,'','','',0,0,'','','','','','10.0.0.51'),(112,'csantivanez-10.0.0.55','4055f4ad2fb210d7a1fb6df3057fe0eb','csantivanez','','127.0.0.1','','','2025-12-05 12:19:45','2025-12-05 12:19:45','2025-12-05 12:30:24',NULL,NULL,'','','',0,0,'','','','','','10.0.0.55');
/*!40000 ALTER TABLE `radacct` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `radcheck`
--

DROP TABLE IF EXISTS `radcheck`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `radcheck` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL DEFAULT '',
  `attribute` varchar(64) NOT NULL DEFAULT '',
  `op` char(2) NOT NULL DEFAULT '==',
  `value` varchar(253) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  KEY `username` (`username`(32))
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `radcheck`
--

LOCK TABLES `radcheck` WRITE;
/*!40000 ALTER TABLE `radcheck` DISABLE KEYS */;
INSERT INTO `radcheck` VALUES (1,'admin@uni.edu','SHA-Password',':=','f865b53623b121fd34ee5426c792e5c33af8c227'),(2,'prof@uni.edu','SHA-Password',':=','b6d2e188b5c4530568e8602f33aee02002d7696f'),(3,'alumno@uni.edu','SHA-Password',':=','0147da78addeee97e9bb8a092afa81785f215a10'),(4,'a20202396@pucp.edu.pe','SHA-Password',':=','931e82b4824f48fc1179fb76c0af28de09823eee'),(5,'csantivanez','SHA-Password',':=','b00768e90e5ebff8e0ba52b81a10d27747d58361');
/*!40000 ALTER TABLE `radcheck` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `radgroupcheck`
--

DROP TABLE IF EXISTS `radgroupcheck`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `radgroupcheck` (
  `id` int(11) unsigned NOT NULL AUTO_INCREMENT,
  `groupname` varchar(64) NOT NULL DEFAULT '',
  `attribute` varchar(64) NOT NULL DEFAULT '',
  `op` char(2) NOT NULL DEFAULT '==',
  `value` varchar(253) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  KEY `groupname` (`groupname`(32))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `radgroupcheck`
--

LOCK TABLES `radgroupcheck` WRITE;
/*!40000 ALTER TABLE `radgroupcheck` DISABLE KEYS */;
/*!40000 ALTER TABLE `radgroupcheck` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `radgroupreply`
--

DROP TABLE IF EXISTS `radgroupreply`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `radgroupreply` (
  `id` int(11) unsigned NOT NULL AUTO_INCREMENT,
  `groupname` varchar(64) NOT NULL DEFAULT '',
  `attribute` varchar(64) NOT NULL DEFAULT '',
  `op` char(2) NOT NULL DEFAULT '=',
  `value` varchar(253) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  KEY `groupname` (`groupname`(32))
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `radgroupreply`
--

LOCK TABLES `radgroupreply` WRITE;
/*!40000 ALTER TABLE `radgroupreply` DISABLE KEYS */;
INSERT INTO `radgroupreply` VALUES (1,'grupo_invitado','Session-Timeout',':=','3600'),(2,'grupo_invitado','Filter-Id',':=','ROLE_GUEST'),(3,'grupo_alumno','Session-Timeout',':=','14400'),(4,'grupo_alumno','Filter-Id',':=','ROLE_STUDENT'),(5,'grupo_profesor','Session-Timeout',':=','28800'),(6,'grupo_profesor','Filter-Id',':=','ROLE_PROFESSOR'),(7,'grupo_investigador','Session-Timeout',':=','86400'),(8,'grupo_investigador','Filter-Id',':=','ROLE_RESEARCHER'),(9,'grupo_admin','Session-Timeout',':=','0'),(10,'grupo_admin','Filter-Id',':=','ROLE_ADMIN');
/*!40000 ALTER TABLE `radgroupreply` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `radpostauth`
--

DROP TABLE IF EXISTS `radpostauth`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `radpostauth` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL DEFAULT '',
  `pass` varchar(64) NOT NULL DEFAULT '',
  `reply` varchar(32) NOT NULL DEFAULT '',
  `authdate` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=228 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `radpostauth`
--

LOCK TABLES `radpostauth` WRITE;
/*!40000 ALTER TABLE `radpostauth` DISABLE KEYS */;
INSERT INTO `radpostauth` VALUES (1,'juan_sql','clave123','Access-Accept','2025-11-23 06:19:39'),(2,'juan_sql','clave123','Access-Accept','2025-11-23 07:14:10'),(3,'juan_sql','prueba123','Access-Reject','2025-11-24 08:10:33'),(4,'juan_sql','prueba123','Access-Reject','2025-11-24 08:10:54'),(5,'juan_sql','clave123','Access-Accept','2025-11-24 08:11:13'),(6,'juan_sql','prueba123','Access-Reject','2025-11-24 08:17:53'),(7,'juan_sql','prueba123','Access-Reject','2025-11-24 08:19:08'),(8,'juan_sql','prueba123','Access-Reject','2025-11-24 08:21:56'),(9,'juan_sql','clave123','Access-Accept','2025-11-24 08:23:23'),(10,'juan_sql','clave123','Access-Accept','2025-11-24 08:32:16'),(11,'juan_sql','prueba123','Access-Reject','2025-11-24 08:32:21'),(12,'juan_sql','clave123','Access-Accept','2025-11-24 08:32:44'),(13,'juan_sql','clave123','Access-Accept','2025-11-24 08:35:49'),(14,'alumno01','pass_alumno','Access-Accept','2025-11-24 20:17:58'),(15,'alumno01','pass_alumno','Access-Accept','2025-11-24 20:22:51'),(16,'profe01','pass_profe','Access-Accept','2025-11-24 20:26:14'),(17,'alumno01','pass_alumno','Access-Accept','2025-11-24 21:21:04'),(18,'admin01','pass_admin','Access-Accept','2025-11-24 21:21:50'),(19,'investigador_x','ciencia2025','Access-Accept','2025-11-24 21:25:18'),(20,'investigador_x','ciencia2025','Access-Accept','2025-11-24 21:31:50'),(21,'admin01','pass_admin','Access-Accept','2025-11-24 21:31:59'),(22,'admin01','pass_admin','Access-Accept','2025-11-25 01:28:21'),(23,'admin01','pass_admin','Access-Accept','2025-11-25 02:16:55'),(24,'investigador_1','1234','Access-Reject','2025-11-25 02:17:27'),(25,'investigador_1','12345','Access-Accept','2025-11-25 02:17:37'),(26,'alumno01','pass_alumno','Access-Accept','2025-11-25 02:40:31'),(27,'super_profe','test','Access-Accept','2025-11-25 19:00:06'),(28,'admin_01','pass_admin','Access-Reject','2025-11-25 19:01:49'),(29,'admin01','pass_admin','Access-Accept','2025-11-25 19:01:57'),(30,'admin01','pass_admin','Access-Accept','2025-11-25 19:05:58'),(31,'admin01','pass_admin','Access-Accept','2025-11-25 19:06:10'),(32,'admin01','pass_admin','Access-Accept','2025-11-25 19:08:54'),(33,'admin01','pass_admin','Access-Accept','2025-11-25 20:47:39'),(34,'multirol','prueba123','Access-Accept','2025-11-25 20:47:52'),(35,'admin01','pass_admin','Access-Accept','2025-11-26 20:16:15'),(36,'admin01','pass_Admin','Access-Reject','2025-11-27 21:05:11'),(37,'admin01','pass_admin','Access-Accept','2025-11-27 21:05:18'),(38,'admin01','pass_admin','Access-Accept','2025-11-27 21:53:43'),(39,'admin01','pass_Admin','Access-Reject','2025-11-27 21:55:21'),(40,'admin01','pass_admin','Access-Accept','2025-11-27 21:55:28'),(41,'admin01','pass_admin','Access-Accept','2025-11-27 21:58:33'),(42,'admin01','pass_Admin','Access-Reject','2025-11-27 21:59:27'),(43,'admin01','pass_admin','Access-Accept','2025-11-27 21:59:35'),(44,'admin01','pass_admin01','Access-Reject','2025-11-27 22:08:01'),(45,'admin01','pass_admin','Access-Accept','2025-11-27 22:08:07'),(46,'admin01','pass_Admin','Access-Reject','2025-11-27 22:20:10'),(47,'admin01','pass_admin','Access-Accept','2025-11-27 22:20:24'),(48,'admin01','pass_admin','Access-Accept','2025-11-27 22:30:27'),(49,'hola@gmail.com','hola123','Access-Accept','2025-11-27 22:30:57'),(50,'admin01','pass_admin','Access-Accept','2025-11-27 22:31:24'),(51,'admin01','passs_Admin','Access-Reject','2025-11-28 00:02:31'),(52,'admin','pass_admin','Access-Reject','2025-11-28 00:02:56'),(53,'admin01','pass_admin','Access-Accept','2025-11-28 00:03:05'),(54,'admin01','pass_admin','Access-Accept','2025-11-28 00:28:38'),(55,'admin01','pass_admin','Access-Accept','2025-11-28 01:25:49'),(56,'admin01','pass_admin','Access-Accept','2025-11-28 01:52:26'),(57,'admin01','pass_admin','Access-Accept','2025-11-28 02:01:24'),(58,'super_profe','test','Access-Accept','2025-11-30 07:46:34'),(59,'alumno01','pass_alumno','Access-Accept','2025-11-30 08:01:55'),(60,'admin01','pass_admin','Access-Accept','2025-11-30 08:11:54'),(61,'admin01','pass_admin','Access-Accept','2025-11-30 08:32:09'),(62,'admin@uni.edu','admin123','Access-Accept','2025-11-30 10:10:41'),(63,'prof@uni.edu','prof123','Access-Accept','2025-11-30 10:11:29'),(64,'alumno@uni.edu','alumno123','Access-Accept','2025-11-30 10:12:10'),(65,'prof@uni.edu','prof123','Access-Accept','2025-11-30 10:21:39'),(66,'admin@uni.edu','admin123','Access-Accept','2025-11-30 10:21:59'),(67,'admin@uni.edu','admin123','Access-Accept','2025-11-30 10:26:21'),(68,'a20202396@pucp.edu.pe','Puke2407','Access-Reject','2025-11-30 10:27:39'),(69,'admin@uni.edu','admin123','Access-Accept','2025-11-30 10:27:55'),(70,'admin@uni.edu','admin123','Access-Accept','2025-11-30 10:39:28'),(71,'admin@uni.edu','admin123','Access-Accept','2025-11-30 10:49:45'),(72,'admin@uni.edu','admin123','Access-Accept','2025-11-30 10:55:26'),(73,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-11-30 10:56:02'),(74,'xczs','safsdd','Access-Reject','2025-12-01 22:54:07'),(75,'xczs','safsdd','Access-Reject','2025-12-02 16:06:23'),(76,'xczs','safsdd','Access-Reject','2025-12-02 16:09:47'),(77,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-02 16:16:46'),(78,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-02 18:15:24'),(79,'20202396@pucp.edu.pe','Puke2407','Access-Reject','2025-12-02 18:19:06'),(80,'20202396@pucp.edu.pe','Puke2407','Access-Reject','2025-12-02 18:19:30'),(81,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-02 18:20:37'),(82,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-02 20:07:45'),(83,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-02 22:45:38'),(84,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-02 22:47:04'),(85,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-02 22:59:57'),(86,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-02 23:10:37'),(87,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-02 23:14:18'),(88,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-02 23:39:45'),(89,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-02 23:45:03'),(90,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-03 00:02:58'),(91,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-03 00:11:40'),(92,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-03 00:54:39'),(93,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-03 02:08:24'),(94,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-03 02:13:17'),(95,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-03 03:08:20'),(96,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-03 03:20:46'),(97,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-03 04:41:17'),(98,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-03 04:45:31'),(99,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-03 21:50:20'),(100,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-03 21:56:38'),(101,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-03 23:33:25'),(102,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 03:18:16'),(103,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 03:21:09'),(104,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 03:21:25'),(105,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 03:42:19'),(106,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 03:46:59'),(107,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 03:48:42'),(108,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 03:49:41'),(109,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 06:07:58'),(110,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 06:11:54'),(111,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 06:16:00'),(112,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 06:29:59'),(113,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 06:48:41'),(114,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 07:21:43'),(115,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 07:44:30'),(116,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 07:45:33'),(117,'csantivanez','santi123','Access-Accept','2025-12-04 07:48:45'),(118,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 08:16:30'),(119,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 09:57:18'),(120,'a20202396@pucp.edu.pe','PUke2407','Access-Reject','2025-12-04 10:15:32'),(121,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 10:15:53'),(122,'csantivanez','santi123','Access-Accept','2025-12-04 10:17:07'),(123,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 11:42:18'),(124,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 11:50:22'),(125,'csantivanez','santi123','Access-Accept','2025-12-04 11:52:50'),(126,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 12:23:28'),(127,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 13:02:40'),(128,'a20202396@pucp.edu.pe','dodoxd','Access-Reject','2025-12-04 19:28:34'),(129,'a20202396@pucp.edu.pe','asdsas','Access-Reject','2025-12-04 19:28:45'),(130,'a20202396@pucp.edu.pe','dasdsadasd','Access-Reject','2025-12-04 19:28:56'),(131,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 19:34:58'),(132,'adsada','adasd','Access-Reject','2025-12-04 19:35:23'),(133,'dasdsad','asdasd','Access-Reject','2025-12-04 19:35:27'),(134,'adsadas','adsdadas','Access-Reject','2025-12-04 19:35:31'),(135,'adasd','asdasd','Access-Reject','2025-12-04 19:41:46'),(136,'dasda','sadasd','Access-Reject','2025-12-04 19:41:49'),(137,'dasdsa','asdasd','Access-Reject','2025-12-04 19:42:00'),(138,'asdsda','asdasd','Access-Reject','2025-12-04 19:48:34'),(139,'asdsad','adsdsa','Access-Reject','2025-12-04 19:48:44'),(140,'dadas','dasda','Access-Reject','2025-12-04 19:48:48'),(141,'asdasd','asdsadas','Access-Reject','2025-12-04 19:56:08'),(142,'dsdsadas','adsadsa','Access-Reject','2025-12-04 19:56:13'),(143,'ssasad','dsadas','Access-Reject','2025-12-04 19:56:16'),(144,'dsadas','dsadas','Access-Reject','2025-12-04 20:11:36'),(145,'dasdsad','asdasd','Access-Reject','2025-12-04 20:11:40'),(146,'dasdas','asdasdad','Access-Reject','2025-12-04 20:11:46'),(147,'dasdasd','dasdasd','Access-Reject','2025-12-04 20:19:10'),(148,'dasdsa','sdsasda','Access-Reject','2025-12-04 20:19:13'),(149,'dsadsad','asdasd','Access-Reject','2025-12-04 20:19:17'),(150,'sadas','dasdsa','Access-Reject','2025-12-04 20:22:35'),(151,'dasdas','asdasd','Access-Reject','2025-12-04 20:22:38'),(152,'adsad','asdsad','Access-Reject','2025-12-04 20:22:41'),(153,'sda','das','Access-Reject','2025-12-04 20:57:14'),(154,'dasdas','sdasd','Access-Reject','2025-12-04 20:57:23'),(155,'sada','dsadas','Access-Reject','2025-12-04 20:57:26'),(156,'asadas','asdsad','Access-Reject','2025-12-04 21:09:50'),(157,'asda','dsada','Access-Reject','2025-12-04 21:11:39'),(158,'dasdasd','asdsad','Access-Reject','2025-12-04 21:11:43'),(159,'asdsa','adsa','Access-Reject','2025-12-04 21:35:36'),(160,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 21:39:16'),(161,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 21:52:42'),(162,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 22:48:05'),(163,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 22:55:17'),(164,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 22:55:17'),(165,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 22:55:18'),(166,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 22:55:19'),(167,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 22:55:19'),(168,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 22:55:20'),(169,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 22:55:20'),(170,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 22:55:21'),(171,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 22:55:21'),(172,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 22:55:22'),(173,'a20202396@pucp.edu.pe','badpass11507','Access-Reject','2025-12-04 22:55:23'),(174,'a20202396@pucp.edu.pe','badpass10880','Access-Reject','2025-12-04 22:55:24'),(175,'a20202396@pucp.edu.pe','badpass29444','Access-Reject','2025-12-04 22:55:25'),(176,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 23:57:00'),(177,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 23:57:01'),(178,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 23:57:01'),(179,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 23:57:02'),(180,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 23:57:02'),(181,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 23:57:03'),(182,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 23:57:03'),(183,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 23:57:04'),(184,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 23:57:05'),(185,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-04 23:57:05'),(186,'a20202396@pucp.edu.pe','badpass25748','Access-Reject','2025-12-04 23:57:06'),(187,'a20202396@pucp.edu.pe','badpass23844','Access-Reject','2025-12-04 23:57:07'),(188,'a20202396@pucp.edu.pe','badpass26499','Access-Reject','2025-12-04 23:57:08'),(189,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 00:32:41'),(190,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 00:38:21'),(191,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 00:42:17'),(192,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 02:07:14'),(193,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 02:25:14'),(194,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 02:52:11'),(195,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 03:03:15'),(196,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 03:05:26'),(197,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 03:22:43'),(198,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 03:28:30'),(199,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 03:29:48'),(200,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 03:37:43'),(201,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 03:44:50'),(202,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 04:01:29'),(203,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 04:18:18'),(204,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 04:55:33'),(205,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 05:23:26'),(206,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 05:26:27'),(207,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 05:45:37'),(208,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 05:50:31'),(209,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 09:36:03'),(210,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 11:26:56'),(211,'csantivanez','santi123','Access-Accept','2025-12-05 11:35:07'),(212,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 11:50:52'),(213,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 12:02:40'),(214,'csantivanez','santi123','Access-Accept','2025-12-05 12:19:12'),(215,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 12:31:03'),(216,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 19:45:21'),(217,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 19:52:16'),(218,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 19:52:36'),(219,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 19:55:03'),(220,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 20:38:22'),(221,'1232121','123123','Access-Reject','2025-12-05 20:39:38'),(222,'a20202396@pucp.edu.pe','1234','Access-Reject','2025-12-05 20:40:16'),(223,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 20:42:16'),(224,'csantivanez','santi123','Access-Accept','2025-12-05 20:45:24'),(225,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 20:46:59'),(226,'csantivanez','santi123','Access-Accept','2025-12-05 20:49:51'),(227,'a20202396@pucp.edu.pe','Puke2407','Access-Accept','2025-12-05 20:56:46');
/*!40000 ALTER TABLE `radpostauth` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `radreply`
--

DROP TABLE IF EXISTS `radreply`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `radreply` (
  `id` int(11) unsigned NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL DEFAULT '',
  `attribute` varchar(64) NOT NULL DEFAULT '',
  `op` char(2) NOT NULL DEFAULT '=',
  `value` varchar(253) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  KEY `username` (`username`(32))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `radreply`
--

LOCK TABLES `radreply` WRITE;
/*!40000 ALTER TABLE `radreply` DISABLE KEYS */;
/*!40000 ALTER TABLE `radreply` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `radusergroup`
--

DROP TABLE IF EXISTS `radusergroup`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `radusergroup` (
  `username` varchar(64) NOT NULL DEFAULT '',
  `groupname` varchar(64) NOT NULL DEFAULT '',
  `priority` int(11) NOT NULL DEFAULT '1',
  KEY `username` (`username`(32))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `radusergroup`
--

LOCK TABLES `radusergroup` WRITE;
/*!40000 ALTER TABLE `radusergroup` DISABLE KEYS */;
INSERT INTO `radusergroup` VALUES ('invitado01','grupo_invitado',1),('alumno01','grupo_alumno',1),('profe01','grupo_profesor',1),('admin01','grupo_admin',1),('investigador_x','grupo_investigador',1),('secreto_man','grupo_alumno',1),('investigador_1','grupo_investigador',1),('multirol','grupo_profesor',1),('hola@gmail.com','grupo_investigador',1),('hola1@gmail.com','grupo_alumno',1);
/*!40000 ALTER TABLE `radusergroup` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2025-12-05 22:28:21
