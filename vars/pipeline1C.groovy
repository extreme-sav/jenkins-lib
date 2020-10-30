import groovy.transform.Field
import ru.pulsar.jenkins.library.configuration.JobConfiguration

import java.util.concurrent.TimeUnit

@Field
JobConfiguration config

@Field
def agent1C

void call() {

    //noinspection GroovyAssignabilityCheck
    pipeline {
        agent none

        options {
            buildDiscarder(logRotator(numToKeepStr: '30'))
            timeout(time: 2, unit: TimeUnit.HOURS)
            timestamps()
            skipDefaultCheckout(true)
        }

        stages {

            stage('pre-stage') {
                agent {
                    label 'agent'
                }

                environment {
                    GIT_LFS_SKIP_SMUDGE=1
                }

                steps {
                    echo "test"
                    checkout scm
                    script {
                        config = jobConfiguration() as JobConfiguration
                        agent1C = config.v8version
                    }
                }
            }

            stage('Подготовка') {
                parallel {
                    stage('Подготовка 1C базы') {
                        agent {
                            label agent1C
                        }
                        when {
                            beforeAgent true
                            expression { config.stageFlags.needInfobase() }
                        }

                        stages {
                            stage('Создание ИБ') {
                                steps {
                                    printLocation()

                                    checkout scm

                                    installLocalDependencies()

                                    createDir('build/out')

                                    // Создание базы загрузкой конфигурации из хранилища
                                    initFromStorage config
                                }
                            }

                            stage('Инициализация ИБ') {
                                when {
                                    beforeAgent true
                                    expression { config.stageFlags.initSteps }
                                }
                                steps {
                                    // Инициализация и первичная миграция
                                    initInfobase config
                                }
                            }

                            stage('Архивация ИБ') {
                                steps {
                                    printLocation()

                                    zipInfobase()
                                }

                            }
                        }

                    }

                    stage('Трансформация в формат EDT') {
                        agent {
                            label 'edt'
                        }
                        when {
                            beforeAgent true
                            expression { config.stageFlags.edtValidate }
                        }
                        steps {
                            checkout scm
                            edtTransform config
                        }
                    }
                }
            }

            stage('Проверка качества') {
                parallel {
                    stage('EDT контроль') {
                        agent {
                            label 'edt'
                        }
                        when {
                            beforeAgent true
                            expression { config.stageFlags.edtValidate }
                        }
                        steps {
                            checkout scm
                            edtValidate config
                        }
                    }

                    stage('BDD сценарии') {
                        agent {
                            label agent1C
                        }
                        when {
                            beforeAgent true
                            expression { config.stageFlags.bdd }
                        }
                        steps {
                            checkout scm
                            unzipInfobase()
                            
                            bdd config
                        }
                    }

                    stage('Синтаксический контроль') {
                        agent {
                            label agent1C
                        }
                        when {
                            beforeAgent true
                            expression { config.stageFlags.syntaxCheck }
                        }
                        steps {
                            checkout scm
                            syntaxCheck config
                        }
                    }

                    stage('Дымовые тесты') {
                        agent {
                            label agent1C
                        }
                        when {
                            beforeAgent true
                            expression { config.stageFlags.smoke }
                        }
                        steps {
                            checkout scm
                            smoke config
                        }
                    }
                }
            }

            stage('Трансформация результатов') {
                agent {
                    label 'oscript'
                }
                when {
                    beforeAgent true
                    expression { config.stageFlags.edtValidate }
                }
                steps {
                    checkout scm
                    transform config
                }
            }

            stage('SonarQube') {
                agent {
                    label 'sonar'
                }
                when {
                    beforeAgent true
                    expression { config.stageFlags.sonarqube }
                }
                steps {
                    checkout scm
                    sonarScanner config
                }
            }
        }

        post('post-stage') {
            always {
                node('agent') {
                    saveResults config
                }
            }
        }
    }

}
