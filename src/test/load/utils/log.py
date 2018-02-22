import logging
from logging.handlers import TimedRotatingFileHandler

# todo - Figure out how to override Locust's logging.basicConfig call so we can control the console output.


def setup_logging():
    squelch_non_critical('urllib3.connectionpool')

    root_logger = logging.getLogger()
    root_logger.setLevel(logging.DEBUG)

    log_format = '%(asctime)s [%(levelname)s] %(name)s : %(message)s'
    formatter = logging.Formatter(log_format, '%Y-%m-%d %H:%M:%S')
    root_logger.addHandler(create_file_handler(formatter))


def create_file_handler(formatter):
    # https://docs.python.org/2/library/logging.handlers.html#timedrotatingfilehandler
    handler = logging.handlers.TimedRotatingFileHandler('/var/log/locust.log', when='midnight', interval=1, encoding='utf-8', utc=True)
    handler.setFormatter(formatter)
    return handler


def squelch_non_critical(name):
    logger = logging.getLogger(name)
    logger.setLevel(logging.WARNING)
