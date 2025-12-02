-- MySQL Workbench Forward Engineering (ajustado para usar solo `sesiones`)

SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0;
SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0;
SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

-- -----------------------------------------------------
-- Schema sesiones
-- -----------------------------------------------------
CREATE SCHEMA IF NOT EXISTS `sesiones` DEFAULT CHARACTER SET utf8mb3 ;
USE `sesiones` ;

-- -----------------------------------------------------
-- Table `sesiones`.`posibles_sesiones`
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `posibles_sesiones` (
  `idposibles_sesiones` INT NOT NULL AUTO_INCREMENT,
  `ip` VARCHAR(45) NULL,
  `mac` VARCHAR(45) NULL,
  `switchid` VARCHAR(45) NULL,
  `inport` VARCHAR(45) NULL,
  PRIMARY KEY (`idposibles_sesiones`)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb3;

-- -----------------------------------------------------
-- Table `sesiones`.`sesiones_activas`
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `sesiones_activas` (
  `idsesiones_activas` INT NOT NULL AUTO_INCREMENT,
  `ip` VARCHAR(45) NOT NULL,
  `mac` VARCHAR(45) NOT NULL,
  `switchid` VARCHAR(45) NOT NULL,
  `inport` VARCHAR(45) NOT NULL,
  `userID` VARCHAR(45) NOT NULL,
  PRIMARY KEY (`idsesiones_activas`)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb3;

SET SQL_MODE=@OLD_SQL_MODE;
SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS;
