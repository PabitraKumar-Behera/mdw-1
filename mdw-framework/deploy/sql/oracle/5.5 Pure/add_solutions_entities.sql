CREATE TABLE SOLUTION
(
  SOLUTION_ID    NUMBER(20)        NOT NULL,
  ID             VARCHAR2(128)     NOT NULL, -- TODO: unique constraint
  NAME           VARCHAR2(1024)    NOT NULL,
  OWNER_TYPE     VARCHAR2(128)     NOT NULL,
  OWNER_ID       VARCHAR2(128)     NOT NULL,  
  CREATE_DT      DATE              DEFAULT SYSDATE,
  CREATE_USR     VARCHAR2(30)      DEFAULT USER,
  MOD_DT         DATE,
  MOD_USR        VARCHAR2(30),
  COMMENTS       VARCHAR2(1024)
);

CREATE TABLE SOLUTION_MAP
(
  SOLUTION_ID    NUMBER(20)        NOT NULL,
  MEMBER_TYPE    VARCHAR2(128)     NOT NULL,
  MEMBER_ID      VARCHAR2(128)     NOT NULL,
  CREATE_DT      DATE              NOT NULL,
  CREATE_USR     VARCHAR(30)       NOT NULL,
  MOD_DT         DATE,
  MOD_USR        VARCHAR2(30),
  COMMENTS       VARCHAR2(1024)
);

ALTER TABLE SOLUTION ADD (
  CONSTRAINT SOLUTION_ID_PK
  PRIMARY KEY (SOLUTION_ID)
  USING INDEX
);

ALTER TABLE SOLUTION_MAP ADD (
  CONSTRAINT SOLUTION_MAP_FK 
  FOREIGN KEY (SOLUTION_ID) 
  REFERENCES SOLUTION(SOLUTION_ID)
);

CREATE TABLE VALUE
(
  NAME            VARCHAR(1024)    NOT NULL,
  VALUE           VARCHAR(2048)    NOT NULL,
  OWNER_TYPE      VARCHAR(128)     NOT NULL,
  OWNER_ID        VARCHAR(128)     NOT NULL,
  CREATE_DT       TIMESTAMP        NOT NULL,
  CREATE_USR      VARCHAR(30)      NOT NULL,
  MOD_DT          DATETIME,
  MOD_USR         VARCHAR(30),
  COMMENTS        VARCHAR(1024)
);


CREATE INDEX SOLUTION_MAP_FK
ON SOLUTION_MAP (SOLUTION_ID);

ALTER TABLE SOLUTION ADD  (   
   UNIQUE(ID) 
);

ALTER TABLE SOLUTION_MAP ADD  
(   
   PRIMARY KEY (SOLUTION_ID,MEMBER_TYPE,MEMBER_ID)
);

ALTER TABLE value ADD  
(   
   PRIMARY KEY (Name,Owner_type,owner_id)
);